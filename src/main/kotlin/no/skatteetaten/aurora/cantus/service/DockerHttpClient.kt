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

val manifestV1 = "application/vnd.docker.distribution.manifest.v1+json"
val manifestV2 = "application/vnd.docker.distribution.manifest.v2+json"

@Service
class DockerHttpClient(
    val webClient: WebClient
) {

    val dockerManfestAccept: List<MediaType> = listOf(
        MediaType.valueOf(manifestV2),
        MediaType.valueOf(manifestV1)
    )

    val uploadUUIDHeader = "Docker-Upload-UUID"
    val dockerContentDigestLabel = "Docker-Content-Digest"

    //TODO: Test error handling here,
    fun generateLocationUrl(
        to: ImageRepoCommand
    ): String {
        val (uuid, _) = webClient
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
                //TODO: This will not work the way it is written now.
                resp.handleStatusCodeError(to.registry)

                val uuidHeader = resp.headers().header(uploadUUIDHeader).firstOrNull()
                    ?: throw SourceSystemException(
                        message = "Response did not contain $uploadUUIDHeader header",
                        sourceSystem = to.registry
                    )
                logger.debug("UUID header=$uuidHeader")
                resp.bodyToMono<JsonNode>().map {
                    logger.debug("Found body=$it")
                    uuidHeader to it
                }.switchIfEmpty(Mono.just(uuidHeader to NullNode.instance))
            }
            .handleError(to)
            .block(Duration.ofSeconds(5)) ?: throw SourceSystemException(
            message = "Response to generate location header did not succeed",
            sourceSystem = to.registry
        )
        return uuid
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
                resp.handleStatusCodeError(imageRepoCommand.registry)

                val dockerContentDigest = resp.headers().header(dockerContentDigestLabel).firstOrNull()
                    ?: throw SourceSystemException(
                        message = "Response did not contain ${this.dockerContentDigestLabel} header",
                        sourceSystem = imageRepoCommand.registry
                    )

                resp.bodyToMono<JsonNode>().map {
                    val contentType = resp.headers().contentType().get().toString()
                    ImageManifestResponseDto(contentType, dockerContentDigest, it)
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
            .blockAndHandleError(imageRepoCommand = imageRepoCommand)
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

    //TODO: Can this be generic with accept header sent in? Either OctetStream og Json?
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

    //TODO: Se om man kan reimplementere dette som retrieve
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
            .bodyToMono<String>()
            .switchIfEmpty(Mono.just("true"))
            .onErrorResume { e ->
                if (e is WebClientResponseException && e.statusCode == HttpStatus.NOT_FOUND) {
                    Mono.just("false")
                } else {
                    Mono.error(e)
                }
            }
            .map {
                it?.toBoolean()
            }
            .blockAndHandleError(imageRepoCommand = imageRepoCommand) ?: false

        /*
        .flatMap { resp ->
            resp.bodyToMono<String>().switchIfEmpty(Mono.just("")).flatMap { body ->
                when (resp.statusCode()) {
                    HttpStatus.NOT_FOUND -> Mono.just(false)
                    HttpStatus.OK -> Mono.just(true)
                    else -> Mono.error(
                        SourceSystemException(
                            message = "Error when checking if blob=$digest exist in repository=${imageRepoCommand.defaultRepo} code=${resp.statusCode().value()}",
                            sourceSystem = imageRepoCommand.registry
                        )
                    )
                }
            }
        }
        .handleError(imageRepoCommand)
        .block(Duration.ofSeconds(5)) ?: false

         */
    }
}