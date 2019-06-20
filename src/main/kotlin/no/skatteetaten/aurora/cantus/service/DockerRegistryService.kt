package no.skatteetaten.aurora.cantus.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
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
import org.springframework.web.reactive.function.client.WebClient.RequestHeadersSpec
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.HashSet

private val logger = KotlinLogging.logger {}

val manifestV1 = "application/vnd.docker.distribution.manifest.v1+json"
val manifestV2 = "application/vnd.docker.distribution.manifest.v2+json"

@Service
class DockerRegistryService(
    val webClient: WebClient,
    val threadPoolContext: ExecutorCoroutineDispatcher
) {

    val dockerManfestAccept: List<MediaType> = listOf(
        MediaType.valueOf(manifestV2),
        MediaType.valueOf(manifestV1)
    )

    val manifestEnvLabels: HashSet<String> = hashSetOf(
        "AURORA_VERSION",
        "IMAGE_BUILD_TIME",
        "APP_VERSION",
        "JOLOKIA_VERSION",
        "JAVA_VERSION_MAJOR",
        "JAVA_VERSION_MINOR",
        "JAVA_VERSION_BUILD",
        "NODEJS_VERSION"
    )

    val dockerVersionLabel = "docker_version"
    val dockerContentDigestLabel = "Docker-Content-Digest"
    val uploadUUIDHeader = "Docker-Upload-UUID"
    val createdLabel = "created"

    /*
    TODO: test with v1
        https://www.danlorenc.com/posts/containers-part-2/
     */
    fun tagImage(from: ImageRepoCommand, to: ImageRepoCommand): Boolean {

        val manifest = getImageManifest(from)
        val layers = findBlobs(manifest)
        /*
        Add test that returns this error when putting manifest. Make sure error is propagated.
        {"errors":[{"code":"BLOB_UNKNOWN","message":"blob unknown to registry","detail":"sha256:303510ed0dee065d6dc0dd4fbb1833aa27ff6177e7dfc72881ea4ea0716c82a1"}]}⏎
         */
        runBlocking(threadPoolContext + MDCContext()) {
            layers.map { digest ->
                async {
                    ensureBlobExist(from, to, digest).also {
                        logger.debug("Blob=$digest pushed to=${to.defaultRepo} success=$it")
                    }
                }
            }.forEach { it.await() }
        }
        return putManifest(to, manifest).also {
            logger.debug("Manifest=$manifest pushed to=${to.fullRepoCommand}")
        }
    }

    private fun ensureBlobExist(from: ImageRepoCommand, to: ImageRepoCommand, digest: String): Boolean {

        if (digestExistInRepo(to, digest)) {
            logger.debug("layer=$digest already exist in registry=${to.defaultRepo}")
            return true
        }

        val uuid = generateLocationUrl(to)
        val data = getBlob(from, digest) ?: return false

        return postLayer(to, uuid, digest, data)
    }

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

    private fun postLayer(
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

    private fun getImageManifest(imageRepoCommand: ImageRepoCommand): ImageManifestResponseDto {

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

    fun getImageTags(imageRepoCommand: ImageRepoCommand): ImageTagsWithTypeDto {
        val url = imageRepoCommand.registry

        val tagsResponse: ImageTagsResponseDto? =
            webClient
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

        if (tagsResponse == null || tagsResponse.tags.isEmpty()) {
            throw SourceSystemException(
                message = "Resource could not be found status=${HttpStatus.NOT_FOUND.value()} message=${HttpStatus.NOT_FOUND.reasonPhrase}",
                sourceSystem = url
            )
        }

        return ImageTagsWithTypeDto(tags = tagsResponse.tags.map {
            ImageTagTypedDto(it)
        })
    }

    private fun imageManifestResponseToImageManifest(
        imageRepoCommand: ImageRepoCommand,
        imageManifestResponse: ImageManifestResponseDto
    ): ImageManifestDto {

        val manifestBody = imageManifestResponse
            .manifestBody
            .checkSchemaCompatibility(
                contentType = imageManifestResponse.contentType,
                imageRepoCommand = imageRepoCommand
            )

        val environmentVariables = manifestBody.getEnvironmentVariablesFromManifest()

        val imageManifestEnvInformation = environmentVariables
            .mapKeys { it.key.toUpperCase() }
            .filter { manifestEnvLabels.contains(it.key) }

        val dockerVersion = manifestBody.getVariableFromManifestBody(dockerVersionLabel)
        val created = manifestBody.getVariableFromManifestBody(createdLabel)

        return ImageManifestDto(
            dockerVersion = dockerVersion,
            dockerDigest = imageManifestResponse.dockerContentDigest,
            buildEnded = created,
            auroraVersion = imageManifestEnvInformation["AURORA_VERSION"],
            nodeVersion = imageManifestEnvInformation["NODEJS_VERSION"],
            appVersion = imageManifestEnvInformation["APP_VERSION"],
            buildStarted = imageManifestEnvInformation["IMAGE_BUILD_TIME"],
            java = JavaImageDto.fromEnvMap(imageManifestEnvInformation),
            jolokiaVersion = imageManifestEnvInformation["JOLOKIA_VERSION"]
        )
    }

    private fun JsonNode.checkSchemaCompatibility(
        contentType: String,
        imageRepoCommand: ImageRepoCommand
    ): JsonNode =
        when (contentType) {
            manifestV2 ->
                this.getV2Information(imageRepoCommand)
            else -> {
                this.getV1CompatibilityFromManifest(imageRepoCommand)
            }
        }

    //TODO: Can this be generic with accept header sent in? Either OctetStream og Json?
    private fun getBlob(
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
            .exchange()
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
    }

    // TODO: This is the same as getBlob above.
    private fun JsonNode.getV2Information(
        imageRepoCommand: ImageRepoCommand
    ): JsonNode {
        //TODO: Hvorfor må vi gjøre dette?
        val configDigest = listOf(
            this.at("/config").get("digest").asText().replace(
                regex = "\\s".toRegex(),
                replacement = ""
            ).split(":").last()
        ).associate { "digest" to "sha256:$it" }

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

    private fun JsonNode.getV1CompatibilityFromManifest(imageRepoCommand: ImageRepoCommand): JsonNode {
        val v1Compatibility =
            this.get("history")?.get(0)?.get("v1Compatibility")?.asText() ?: throw SourceSystemException(
                message = "Body of v1 manifest is empty for image ${imageRepoCommand.manifestRepo}",
                sourceSystem = imageRepoCommand.registry
            )

        return jacksonObjectMapper().readTree(v1Compatibility)
    }

    private fun JsonNode.getVariableFromManifestBody(label: String) = this.get(label)?.asText() ?: ""
    fun findBlobs(manifest: ImageManifestResponseDto): List<String> {
        return if (manifest.contentType == manifestV2) {
            val layers: ArrayNode = manifest.manifestBody["layers"] as ArrayNode
            layers.map { it["digest"].textValue() } + manifest.manifestBody.at("/config/digest").textValue()
        } else {
            val layers: ArrayNode = manifest.manifestBody["fsLayers"] as ArrayNode
            layers.map { it["blobSum"].textValue() }
        }
    }

    fun getImageManifestInformation(
        imageRepoCommand: ImageRepoCommand
    ): ImageManifestDto {
        val dockerResponse = getImageManifest(imageRepoCommand)

        return imageManifestResponseToImageManifest(
            imageRepoCommand = imageRepoCommand,
            imageManifestResponse = dockerResponse
        )
    }
}

private fun JsonNode.getEnvironmentVariablesFromManifest() =
    this.at("/config/Env").associate {
        val (key, value) = it.asText().split("=")
        key to value
    }
