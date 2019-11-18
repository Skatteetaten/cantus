package no.skatteetaten.aurora.cantus.service

import com.fasterxml.jackson.databind.JsonNode
import mu.KotlinLogging
import no.skatteetaten.aurora.cantus.controller.ImageRepoCommand
import no.skatteetaten.aurora.cantus.controller.SourceSystemException
import no.skatteetaten.aurora.cantus.controller.blockAndHandleError
import no.skatteetaten.aurora.cantus.controller.blockAndHandleErrorWithRetry
import no.skatteetaten.aurora.cantus.controller.handleError
import no.skatteetaten.aurora.cantus.controller.handleStatusCodeError
import no.skatteetaten.aurora.cantus.controller.retryRepoCommand
import no.skatteetaten.aurora.cantus.createObjectMapper
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpHeaders.EMPTY
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.body
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import java.time.Duration
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

private val logger = KotlinLogging.logger {}

const val MANIFEST_V2 = "application/vnd.docker.distribution.manifest.v2+json"

const val UPLOAD_UUID_HEADER_LABEL = "Docker-Upload-UUID"
const val DOCKER_CONTENT_DIGEST_HEADER_LABEL = "Docker-Content-Digest"

private const val BLOCK_TIMEOUT_IN_SECONDS = 5L
private val BLOCK_TIMEOUT_DURATION = Duration.ofSeconds(BLOCK_TIMEOUT_IN_SECONDS)

@ExperimentalContracts
private inline fun validResponse(
    value: Boolean,
    sourceSystem: String,
    logMessage: String? = null,
    lazyMessage: () -> Any
) {
    contract {
        returns() implies value
    }
    if (!value) {
        if (logMessage != null) {
            logger.info { logMessage }
        }
        val message = lazyMessage()
        throw SourceSystemException(message = message.toString(), sourceSystem = sourceSystem)
    }
}

@Service
class DockerHttpClient(
    val webClient: WebClient
) {
    val dockerManifestAccept: List<MediaType> = listOf(
        MediaType.valueOf(MANIFEST_V2)
    )

    fun getUploadUUID(
        to: ImageRepoCommand
    ): String {
        val (_, headers) = to.createRequest(
            webClient = webClient,
            method = HttpMethod.POST,
            path = "{imageGroup}/{imageName}/blobs/uploads/"
        )
            .headers { headers ->
                headers.contentLength = 0L
            }
            .exchange()
            .performBodyAndHeader(to)

        return headers[UPLOAD_UUID_HEADER_LABEL]?.first() ?: throw SourceSystemException(
            message = "Response to generate UUID header did not succeed",
            sourceSystem = to.registry
        )
    }

    fun putManifest(
        to: ImageRepoCommand,
        manifest: ImageManifestResponseDto
    ): Boolean {
        val manifestBody = createObjectMapper().writeValueAsString(manifest.manifestBody)
        return to.createRequest(
            webClient = webClient,
            method = HttpMethod.PUT,
            path = "{imageGroup}/{imageName}/manifests/{imageTag}"
        )
            .headers { headers ->
                headers.contentType = MediaType.valueOf(manifest.contentType)
            }
            .body(BodyInserters.fromValue(manifestBody))
            .retrieve()
            .bodyToMono<JsonNode>()
            .blockAndHandleErrorWithRetry(
                "operation=PUT_MANIFEST registry=${to.fullRepoCommand}  " +
                    "manifest=$manifestBody contentType=${manifest.contentType}",
                to
            ).let { true }
    }

    fun uploadLayer(
        to: ImageRepoCommand,
        uuid: String,
        digest: String,
        data: Mono<ByteArray>
    ): Boolean {
        return to.createRequest(
            webClient = webClient,
            method = HttpMethod.PUT, path = "{imageGroup}/{imageName}/blobs/uploads/{uuid}?digest={digest}",
            pathVariables = mapOf("uuid" to uuid, "digest" to digest)
        )
            .body(data)
            .headers { headers ->
                headers.contentType = MediaType.APPLICATION_OCTET_STREAM
            }
            .retrieve()
            .bodyToMono<JsonNode>()
            .blockAndHandleErrorWithRetry(
                "operation=UPLOAD_LAYER registry=${to.artifactRepo} uuid=$uuid digest=$digest",
                to
            ).let { true }
    }

    @ExperimentalContracts
    fun getImageManifest(imageRepoCommand: ImageRepoCommand): ImageManifestResponseDto {
        require(!imageRepoCommand.imageTag.isNullOrEmpty()) { "Invalid url=${imageRepoCommand.fullRepoCommand}" }

        val (body, headers) = imageRepoCommand
            .createRequest(webClient, "{imageGroup}/{imageName}/manifests/{imageTag}")
            .headers {
                it.accept = dockerManifestAccept
            }.exchange()
            .retryRepoCommand("operation=GET_MANIFEST registry=${imageRepoCommand.fullRepoCommand}")
            .performBodyAndHeader(imageRepoCommand)

        val (manifest, contentDigestLabel, contentType) =
            parseManifestVariables(body, headers, imageRepoCommand)

        return ImageManifestResponseDto(contentType, contentDigestLabel, manifest)
    }

    @ExperimentalContracts
    private fun parseManifestVariables(
        body: JsonNode?,
        headers: HttpHeaders,
        imageRepoCommand: ImageRepoCommand
    ): Triple<JsonNode, String, String> {
        val manifest: JsonNode? = body
        val contentDigestLabel: String? =
            headers[DOCKER_CONTENT_DIGEST_HEADER_LABEL]?.first()
        val contentType = headers["Content-Type"]?.first()

        validResponse(
            value = manifest != null,
            sourceSystem = imageRepoCommand.registry
        ) { "Manifest not found for image ${imageRepoCommand.manifestRepo}" }

        validResponse(value = contentType == MANIFEST_V2, sourceSystem = imageRepoCommand.registry) {
            "Only v2 manifest is supported. contentType=$contentType" +
                " image=${imageRepoCommand.artifactRepo}:${imageRepoCommand.imageTag}"
        }

        validResponse(
            value = contentDigestLabel != null,
            sourceSystem = imageRepoCommand.registry,
            logMessage = "Old image manifest detected for" +
                "image=${imageRepoCommand.artifactRepo}:${imageRepoCommand.imageTag}"
        ) {
            "Required header=$DOCKER_CONTENT_DIGEST_HEADER_LABEL is not present"
        }
        return Triple(manifest, contentDigestLabel, contentType)
    }

    fun getImageTags(imageRepoCommand: ImageRepoCommand): ImageTagsResponseDto? = imageRepoCommand
        .createRequest(webClient = webClient, path = "/{imageGroup}/{imageName}/tags/list")
        .retrieve()
        .bodyToMono<ImageTagsResponseDto>()
        .blockAndHandleError(imageRepoCommand = imageRepoCommand)

    fun getConfig(imageRepoCommand: ImageRepoCommand, digest: String) =
        this.getBlob(
            imageRepoCommand, digest
        )?.let {
            createObjectMapper().readTree(it)
        } ?: throw SourceSystemException(
            message = "Unable to retrieve V2 manifest for ${imageRepoCommand.artifactRepo}/$digest",
            sourceSystem = imageRepoCommand.registry
        )

    fun getLayer(imageRepoCommand: ImageRepoCommand, digest: String): Mono<ByteArray> =
        imageRepoCommand.createRequest(
            webClient = webClient,
            path = "{imageGroup}/{imageName}/blobs/{digest}",
            pathVariables = mapOf("digest" to digest)
        )
            .retrieve()
            .bodyToMono()

    private fun getBlob(
        imageRepoCommand: ImageRepoCommand,
        digest: String
    ): ByteArray? {
        return imageRepoCommand.createRequest(
            webClient = webClient,
            path = "{imageGroup}/{imageName}/blobs/{digest}",
            pathVariables = mapOf("digest" to digest)
        )
            .retrieve()
            .bodyToMono<ByteArray>()
            .blockAndHandleErrorWithRetry(
                "operation=GET_BLOB registry=${imageRepoCommand.artifactRepo}",
                imageRepoCommand
            )
    }

    fun digestExistInRepo(
        imageRepoCommand: ImageRepoCommand,
        digest: String
    ): Boolean {
        val result = imageRepoCommand.createRequest(
            webClient = webClient,
            method = HttpMethod.HEAD,
            path = "{imageGroup}/{imageName}/blobs/$digest"
        )
            .retrieve()
            .bodyToMono<ByteArray>()
            .map { true } // We need this to turn it into a boolean
            .switchIfEmpty(Mono.just(true))
            .onErrorResume { e ->
                if (e is WebClientResponseException && e.statusCode == HttpStatus.NOT_FOUND) {
                    Mono.just(false)
                } else {
                    Mono.error(e)
                }
            }
            .blockAndHandleErrorWithRetry(
                "operation=BLOB_EXIST registry=${imageRepoCommand.artifactRepo} digest=$digest",
                imageRepoCommand
            )
        return result ?: false
    }

    private fun Mono<ClientResponse>.performBodyAndHeader(imageRepoCommand: ImageRepoCommand) =
        this.flatMap { resp: ClientResponse ->
            if (!resp.statusCode().is2xxSuccessful) {
                resp.handleStatusCodeError<Pair<JsonNode?, HttpHeaders>>(imageRepoCommand.registry)
            } else {
                resp.bodyToMono<JsonNode>().map { body ->
                    body to resp.headers().asHttpHeaders()
                }.switchIfEmpty(Mono.just(null to resp.headers().asHttpHeaders()))
            }
        }.handleError(imageRepoCommand)
            .block(BLOCK_TIMEOUT_DURATION)
            ?: null to EMPTY
    // The line above must be here to get the types to align
    // but it will never happen. We switch on Empty in the flagMap block
}
