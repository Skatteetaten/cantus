package no.skatteetaten.aurora.cantus.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import mu.KotlinLogging
import no.skatteetaten.aurora.cantus.controller.BadRequestException
import no.skatteetaten.aurora.cantus.controller.ImageRepoCommand
import no.skatteetaten.aurora.cantus.controller.SourceSystemException
import no.skatteetaten.aurora.cantus.controller.blockAndHandleError
import no.skatteetaten.aurora.cantus.controller.handleError
import no.skatteetaten.aurora.cantus.controller.handleStatusCodeError
import no.skatteetaten.aurora.cantus.createObjectMapper
import org.springframework.http.HttpHeaders.AUTHORIZATION
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.BodyInserters
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
        return webClient
            .post()
            .uri(
                "${to.url}/{imageGroup}/{imageName}/blobs/uploads/",
                to.mappedTemplateVars
            )
            .headers { headers ->
                to.token?.let {
                    headers.set(AUTHORIZATION, "${to.authType} $it")
                }
                headers.contentLength = 0L
            }
            .exchange()
            .flatMap { resp ->
                if (!resp.statusCode().is2xxSuccessful) {
                    resp.handleStatusCodeError<String>(to.registry)
                } else {
                    resp.bodyToMono<JsonNode>()
                        .switchIfEmpty(Mono.just(NullNode.instance))
                        .flatMap {
                            val uuidHeader = resp.headers().header(uploadUUIDHeader).firstOrNull()
                            if (uuidHeader == null) {
                                Mono.error<String>(
                                    SourceSystemException(
                                        message = "Response did not contain $uploadUUIDHeader header",
                                        sourceSystem = to.registry
                                    )
                                )
                            } else {
                                Mono.just(uuidHeader)
                            }
                        }
                }
            }
            .handleError(to)
            .block(Duration.ofSeconds(5)) ?: throw SourceSystemException(
            message = "Response to generate location header did not succeed",
            sourceSystem = to.registry
        )
    }

    fun putManifest(
        to: ImageRepoCommand,
        manifest: ImageManifestResponseDto
    ): Boolean {
        return webClient
            .put()
            .uri(
                "${to.url}/{imageGroup}/{imageName}/manifests/{imageTag}",
                to.mappedTemplateVars
            )
            .headers { headers ->
                to.token?.let {
                    headers.set(AUTHORIZATION, "${to.authType} $it")
                }
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
        return webClient
            .put()
            .uri(
                "${to.url}/{imageGroup}/{imageName}/blobs/uploads/{uuid}?digest={digest}",
                to.mappedTemplateVars + mapOf("uuid" to uuid, "digest" to digest)
            )
            .body(BodyInserters.fromObject(data))
            .headers { headers ->
                headers.contentType = MediaType.APPLICATION_OCTET_STREAM
                to.token?.let {
                    headers.set(AUTHORIZATION, "${to.authType} $it")
                }
            }
            .retrieve()
            .bodyToMono<JsonNode>()
            .blockAndHandleError(imageRepoCommand = to)?.let { true } ?: false
    }

    fun getImageManifest(imageRepoCommand: ImageRepoCommand): ImageManifestResponseDto {

        if (imageRepoCommand.imageTag.isNullOrEmpty()) throw BadRequestException("Invalid url=${imageRepoCommand.fullRepoCommand}")

        return webClient
            .get()
            .uri(
                "${imageRepoCommand.url}/{imageGroup}/{imageName}/manifests/{imageTag}",
                imageRepoCommand.mappedTemplateVars
            )
            .headers {
                it.accept = dockerManfestAccept
            }
            .headers { headers ->
                imageRepoCommand.token?.let {
                    headers.set(AUTHORIZATION, "${imageRepoCommand.authType} $it")
                }
            }
            .exchange()
            .flatMap { resp ->
                if (!resp.statusCode().is2xxSuccessful) {
                    resp.handleStatusCodeError<ImageManifestResponseDto>(imageRepoCommand.registry)
                } else {
                    resp.bodyToMono<JsonNode>().flatMap { body ->
                        val dockerContentDigest: String? =
                            resp.headers().header(dockerContentDigestLabel).firstOrNull()
                        val contentType: String? =
                            resp.headers().contentType().map { it.toString() }.orElseGet { null }
                        when {
                            dockerContentDigest == null -> Mono.error<ImageManifestResponseDto>(SourceSystemException("Required header $dockerContentDigestLabel is not present"))
                            contentType == null -> Mono.error<ImageManifestResponseDto>(SourceSystemException("Required header Content-Type is not present"))
                            else -> Mono.just(ImageManifestResponseDto(contentType, dockerContentDigest, body))
                        }
                    }
                }
            }
            .handleError(imageRepoCommand)
            .block(Duration.ofSeconds(5))

            ?: throw SourceSystemException(
                message = "Manifest not found for image ${imageRepoCommand.manifestRepo}",
                sourceSystem = imageRepoCommand.registry
            )
    }

    fun getImageTags(imageRepoCommand: ImageRepoCommand): ImageTagsResponseDto? {

        return webClient
            .get()
            .uri(
                "${imageRepoCommand.url}/{imageGroup}/{imageName}/tags/list",
                imageRepoCommand.mappedTemplateVars
            )
            .headers { headers ->
                imageRepoCommand.token?.let {
                    headers.set(AUTHORIZATION, "${imageRepoCommand.authType} $it")
                }
            }
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
        return webClient
            .get()
            .uri(
                "${imageRepoCommand.url}/{imageGroup}/{imageName}/blobs/{digest}",
                imageRepoCommand.mappedTemplateVars + configDigest
            )
            .headers { headers ->
                imageRepoCommand.token?.let {
                    headers.set(AUTHORIZATION, "${imageRepoCommand.authType} $it")
                }
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
        return webClient
            .get()
            .uri(
                "${imageRepoCommand.url}/{imageGroup}/{imageName}/blobs/{digest}",
                imageRepoCommand.mappedTemplateVars + mapOf("digest" to digest)
            )
            .headers { headers ->
                imageRepoCommand.token?.let {
                    headers.set(AUTHORIZATION, "${imageRepoCommand.authType} $it")
                }
            }
            .retrieve()
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
        return webClient
            .head()
            .uri(
                "${imageRepoCommand.url}/{imageGroup}/{imageName}/blobs/{digest}",
                imageRepoCommand.mappedTemplateVars + mapOf("digest" to digest)
            )
            .headers { headers ->
                imageRepoCommand.token?.let {
                    headers.set(AUTHORIZATION, "${imageRepoCommand.authType} $it")
                }
            }
            .retrieve()
            .exist()
            .blockAndHandleError(imageRepoCommand = imageRepoCommand) ?: false
    }
}