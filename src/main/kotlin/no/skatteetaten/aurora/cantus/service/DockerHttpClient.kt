package no.skatteetaten.aurora.cantus.service

import com.fasterxml.jackson.databind.JsonNode
import mu.KotlinLogging
import no.skatteetaten.aurora.cantus.controller.BadRequestException
import no.skatteetaten.aurora.cantus.controller.ImageRepoCommand
import no.skatteetaten.aurora.cantus.controller.SourceSystemException
import no.skatteetaten.aurora.cantus.controller.blockAndHandleError
import no.skatteetaten.aurora.cantus.controller.handleError
import no.skatteetaten.aurora.cantus.controller.handleStatusCodeError
import no.skatteetaten.aurora.cantus.createObjectMapper
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpHeaders.AUTHORIZATION
import org.springframework.http.HttpHeaders.EMPTY
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import java.time.Duration

private val logger = KotlinLogging.logger {}

const val manifestV1 = "application/vnd.docker.distribution.manifest.v1+json"
const val manifestV2 = "application/vnd.docker.distribution.manifest.v2+json"

const val uploadUUIDHeader = "Docker-Upload-UUID"
const val dockerContentDigestLabel = "Docker-Content-Digest"

@Service
class DockerHttpClient(
    val webClient: WebClient
) {
    val dockerManfestAccept: List<MediaType> = listOf(
        MediaType.valueOf(manifestV2),
        MediaType.valueOf(manifestV1)
    )

    fun getUploadUUID(
        to: ImageRepoCommand
    ): String {
        val (_, headers) = to.createRequest(
            method = HttpMethod.PUT,
            path = "{imageGroup}/{imageName}/blobs/uploads/"
        )
            .headers { headers ->
                headers.contentLength = 0L
            }
            .exchange()
            .performBodyAndHeader(to)

        return headers[uploadUUIDHeader]?.first() ?: throw SourceSystemException(
            message = "Response to generate UUID header did not succeed",
            sourceSystem = to.registry
        )
    }

    fun putManifest(
        to: ImageRepoCommand,
        manifest: ImageManifestResponseDto
    ): Boolean {
        return to.createRequest(method = HttpMethod.PUT, path = "{imageGroup}/{imageName}/manifests/{imageTag}")
            .headers { headers ->
                headers.contentType = MediaType.valueOf(manifest.contentType)
            }
            .body(BodyInserters.fromObject(createObjectMapper().writeValueAsString(manifest.manifestBody)))
            .retrieve()
            .bodyToMono<JsonNode>()
            .blockAndHandleError(imageRepoCommand = to)
            ?.let { true } ?: false
    }

    fun postLayer(
        to: ImageRepoCommand,
        uuid: String,
        digest: String,
        data: ByteArray
    ): Boolean {
        return to.createRequest(
            method = HttpMethod.PUT, path = "{imageGroup}/{imageName}/blobs/uploads/{uuid}?digest={digest}",
            pathVariables = mapOf("uuid" to uuid, "digest" to digest)
        )
            .body(BodyInserters.fromObject(data))
            .headers { headers ->
                headers.contentType = MediaType.APPLICATION_OCTET_STREAM
            }
            .retrieve()
            .bodyToMono<JsonNode>()
            .blockAndHandleError(imageRepoCommand = to)?.let { true } ?: false
    }

    fun Mono<ClientResponse>.performBodyAndHeader(imageRepoCommand: ImageRepoCommand) =
        this.flatMap { resp: ClientResponse ->
            if (!resp.statusCode().is2xxSuccessful) {
                resp.handleStatusCodeError<Pair<JsonNode?, HttpHeaders>>(imageRepoCommand.registry)
            } else {
                resp.bodyToMono<JsonNode>().map { body ->
                    body to resp.headers().asHttpHeaders()
                }.switchIfEmpty(Mono.just(null to resp.headers().asHttpHeaders()))
            }
        }.handleError(imageRepoCommand)
            .block(Duration.ofSeconds(5))
            ?: null to EMPTY

    private fun ImageRepoCommand.createRequest(
        path: String,
        method: HttpMethod = HttpMethod.GET,
        pathVariables: Map<String, String> = emptyMap()
    ) = webClient
        .method(method)
        .uri(
            "${this.url}/$path",
            this.mappedTemplateVars + pathVariables
        )
        .headers { headers ->
            this.token?.let {
                headers.set(AUTHORIZATION, "${this.authType} $it")
            }
        }

    fun getImageManifest(imageRepoCommand: ImageRepoCommand): ImageManifestResponseDto {

        if (imageRepoCommand.imageTag.isNullOrEmpty()) throw BadRequestException("Invalid url=${imageRepoCommand.fullRepoCommand}")

        val (body, headers) = imageRepoCommand.createRequest("{imageGroup}/{imageName}/manifests/{imageTag}")
            .headers {
                it.accept = dockerManfestAccept
            }.exchange()
            .performBodyAndHeader(imageRepoCommand)

        val manifest: JsonNode = body ?: throw SourceSystemException(
            message = "Manifest not found for image ${imageRepoCommand.manifestRepo}",
            sourceSystem = imageRepoCommand.registry
        )
        val contentType = headers["Content-Type"]?.first() ?: throw SourceSystemException(
            message = "Required header=Content-Type is not present",
            sourceSystem = imageRepoCommand.registry
        )

        val contentDigestLabel: String = headers[dockerContentDigestLabel]?.first() ?: throw SourceSystemException(
            message = "Required header=$dockerContentDigestLabel is not present",
            sourceSystem = imageRepoCommand.registry
        )

        return ImageManifestResponseDto(contentType, contentDigestLabel, manifest)
    }

    fun getImageTags(imageRepoCommand: ImageRepoCommand): ImageTagsResponseDto? {

        return imageRepoCommand.createRequest("/{imageGroup}/{imageName}/tags/list")
            .retrieve()
            .bodyToMono<ImageTagsResponseDto>()
            .blockAndHandleError(duration = Duration.ofSeconds(1), imageRepoCommand = imageRepoCommand)
    }

    /*
    //TODO: Need to add this retry where it is needed
        .retryExponentialBackoff(
            times = 3,
            first = Duration.ofMillis(100),
            max = Duration.ofSeconds(1),
            doOnRetry = {
                val e = it.exception()
                val registry = imageRepoCommand.registry
                val exceptionClass = e::class.simpleName
                if (it.iteration() == 3L) {
                    logger.warn(e) {
                        "Last retry to registry=$registry, previous failed with exception=$exceptionClass"
                    }
                } else {
                    logger.info {
                        "Retry=${it.iteration()} for request to registry=$registry, previous failed with exception=$exceptionClass - message=\"${e.message}\""
                    }
                }
            }
        )
     */
    fun getLayer(
        imageRepoCommand: ImageRepoCommand,
        configDigest: Map<String, String>
    ): JsonNode {
        return imageRepoCommand.createRequest("{imageGroup}/{imageName}/blobs/{digest}")
            .headers { headers ->
                headers.accept = listOf(MediaType.valueOf("application/json"))
            }
            .retrieve()
            .bodyToMono<JsonNode>()
            .blockAndHandleError(imageRepoCommand = imageRepoCommand)
            ?: throw SourceSystemException(
                message = "Unable to retrieve V2 manifest for ${imageRepoCommand.defaultRepo}/:$configDigest",
                sourceSystem = imageRepoCommand.registry
            )
    }

    fun getBlob(
        imageRepoCommand: ImageRepoCommand,
        digest: String
    ): ByteArray? {
        return imageRepoCommand.createRequest(
            path = "{imageGroup}/{imageName}/blobs/{digest}",
            pathVariables = mapOf("digest" to digest)
        ).retrieve()
            .bodyToMono<ByteArray>()
            .blockAndHandleError(imageRepoCommand = imageRepoCommand)
    }

    /*
      When doing a Head Request you get an empy body back
       empty body == true
       404 error == false
       else == error
     */
    fun WebClient.ResponseSpec.exist() =
        this.bodyToMono<Boolean>()
            .switchIfEmpty(Mono.just(true))
            .onErrorResume { e ->
                if (e is WebClientResponseException && e.statusCode == HttpStatus.NOT_FOUND) {
                    Mono.just(false)
                } else {
                    Mono.error(e)
                }
            }

    fun digestExistInRepo(
        imageRepoCommand: ImageRepoCommand,
        digest: String
    ): Boolean {
        return imageRepoCommand.createRequest(
            method = HttpMethod.HEAD,
            path = "{imageGroup}/{imageName}/blobs/{digest}",
            pathVariables = mapOf("digest" to digest)
        )
            .retrieve()
            .exist()
            .blockAndHandleError(imageRepoCommand = imageRepoCommand) ?: false
    }
}