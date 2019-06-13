package no.skatteetaten.aurora.cantus.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
    val registryMetadataResolver: RegistryMetadataResolver,
    val imageRegistryUrlBuilder: ImageRegistryUrlBuilder
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

        val (_, manifest) = getImageManifest(from)
        logger.debug("Found manifest=$manifest")

        val layers = findBlobs(manifest)
        logger.debug("found layers=$layers")

        /*
        Add test that returns this error when puting manifest. Make sure error is propagated.
        {"errors":[{"code":"BLOB_UNKNOWN","message":"blob unknown to registry","detail":"sha256:303510ed0dee065d6dc0dd4fbb1833aa27ff6177e7dfc72881ea4ea0716c82a1"}]}⏎
         */
        // TODO: do in parallel
        // TODO: missing config
        layers.forEach { digest ->
            ensureBlobExist(from, to, digest).also {
                logger.debug("Blob=$digest pushed to=$to success=$it")
            }
        }

        val toRegistryMetadata = registryMetadataResolver.getMetadataForRegistry(to.registry)
        return putManifest(to, toRegistryMetadata, manifest).also {
            logger.debug("Manifest=$manifest pushed to=$to")
        }
    }

    private fun ensureBlobExist(from: ImageRepoCommand, to: ImageRepoCommand, digest: String): Boolean {

        val toRegistryMetadata = registryMetadataResolver.getMetadataForRegistry(to.registry)
        val fromRegistryMethod = registryMetadataResolver.getMetadataForRegistry(from.registry)

        if (digestExistInRepo(to, toRegistryMetadata, digest)) {
            logger.debug("layer=$digest already exist in registry=$to")
            return true
        }

        val uuid = generateLocationUrl(to, toRegistryMetadata)
        logger.debug("UUID=$uuid")
        val data = getLayer(from, fromRegistryMethod, digest) ?: return false

        return postLayer(to, toRegistryMetadata, uuid, digest, data)
    }

    private fun generateLocationUrl(
        to: ImageRepoCommand,
        toRegistryMetadata: RegistryMetadata
    ): String {
        val (uuid, _) = webClient
            .post()
            .uri(
                imageRegistryUrlBuilder.createUploadUrl(to, toRegistryMetadata),
                to.mappedTemplateVars
            )
            .headers { headers ->
                to.bearerToken?.let {
                    headers.set(AUTHORIZATION, "Basic $it")
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

    private fun putManifest(
        to: ImageRepoCommand,
        toRegistryMetadata: RegistryMetadata,
        manifest: ImageManifestResponseDto
    ): Boolean {
        return webClient
            .put()
            .uri(
                imageRegistryUrlBuilder.createManifestUrl(to, toRegistryMetadata),
                to.mappedTemplateVars
            )
            .headers { headers ->
                to.bearerToken?.let {
                    headers.set(AUTHORIZATION, "Basic $it")
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
        toRegistryMetadata: RegistryMetadata,
        uuid: String,
        digest: String,
        data: ByteArray
    ): Boolean {
        return webClient
            .put()
            .uri(
                imageRegistryUrlBuilder.createUploadLayerUrl(to, toRegistryMetadata),
                to.mappedTemplateVars + mapOf("uuid" to uuid, "digest" to digest)
            )
            .body(BodyInserters.fromObject(data))
            .headers { headers ->
                headers.contentType = MediaType.APPLICATION_OCTET_STREAM
                to.bearerToken?.let {
                    headers.set(AUTHORIZATION, "Basic $it")
                }
            }
            .retrieve()
            .bodyToMono<JsonNode>()
            .blockAndHandleError(imageRepoCommand = to)?.let { true } ?: false
    }

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
        val (registryMetadata, dockerResponse) = getImageManifest(imageRepoCommand)

        return imageManifestResponseToImageManifest(
            imageRepoCommand = imageRepoCommand,
            imageManifestResponse = dockerResponse,
            imageRegistryMetadata = registryMetadata
        )
    }

    private fun getImageManifest(imageRepoCommand: ImageRepoCommand): Pair<RegistryMetadata, ImageManifestResponseDto> {
        val url = imageRepoCommand.registry

        if (imageRepoCommand.imageTag.isNullOrEmpty()) throw BadRequestException("Invalid url=${imageRepoCommand.fullRepoCommand}")

        val registryMetadata = registryMetadataResolver.getMetadataForRegistry(url)

        val dockerResponse = getManifestFromRegistry(imageRepoCommand) { webClient ->
            webClient
                .get()
                .uri(
                    imageRegistryUrlBuilder.createManifestUrl(imageRepoCommand, registryMetadata),
                    imageRepoCommand.mappedTemplateVars
                )
                .headers {
                    it.accept = dockerManfestAccept
                }
        } ?: throw SourceSystemException(
            message = "Manifest not found for image ${imageRepoCommand.manifestRepo}",
            sourceSystem = url
        )
        return Pair(registryMetadata, dockerResponse)
    }

    fun getImageTags(imageRepoCommand: ImageRepoCommand): ImageTagsWithTypeDto {
        val url = imageRepoCommand.registry

        val registryMetadata = registryMetadataResolver.getMetadataForRegistry(url)

        val tagsResponse: ImageTagsResponseDto? =
            getBodyFromDockerRegistry(imageRepoCommand) { webClient ->
                logger.debug("Retrieving tags from $url")
                webClient
                    .get()
                    .uri(
                        imageRegistryUrlBuilder.createTagsUrl(imageRepoCommand, registryMetadata),
                        imageRepoCommand.mappedTemplateVars
                    )
            }

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

    private final inline fun <reified T : Any> getBodyFromDockerRegistry(
        imageRepoCommand: ImageRepoCommand,
        fn: (WebClient) -> WebClient.RequestHeadersSpec<*>
    ): T? = fn(webClient)
        .headers { headers ->
            imageRepoCommand.bearerToken?.let {
                headers.setBearerAuth(it)
            }
        }
        .retrieve()
        .bodyToMono<T>()
        .blockAndHandleError(imageRepoCommand = imageRepoCommand)

    private fun getManifestFromRegistry(
        imageRepoCommand: ImageRepoCommand,
        fn: (WebClient) -> WebClient.RequestHeadersSpec<*>
    ): ImageManifestResponseDto? = fn(webClient)
        .headers { headers ->
            imageRepoCommand.bearerToken?.let {
                headers.setBearerAuth(it)
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

    private fun imageManifestResponseToImageManifest(
        imageRepoCommand: ImageRepoCommand,
        imageManifestResponse: ImageManifestResponseDto,
        imageRegistryMetadata: RegistryMetadata
    ): ImageManifestDto {

        val manifestBody = imageManifestResponse
            .manifestBody
            .checkSchemaCompatibility(
                contentType = imageManifestResponse.contentType,
                imageRepoCommand = imageRepoCommand,
                imageRegistryMetadata = imageRegistryMetadata
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
        imageRepoCommand: ImageRepoCommand,
        imageRegistryMetadata: RegistryMetadata
    ): JsonNode =
        when (contentType) {
            manifestV2 ->
                this.getV2Information(imageRepoCommand, imageRegistryMetadata)
            else -> {
                this.getV1CompatibilityFromManifest(imageRepoCommand)
            }
        }

    private fun getLayer(
        imageRepoCommand: ImageRepoCommand,
        registryMetadata: RegistryMetadata,
        digest: String
    ): ByteArray? {
        return getBodyFromDockerRegistry(imageRepoCommand) { webClient ->
            webClient
                .get()
                .uri(
                    imageRegistryUrlBuilder.createBlobUrl(
                        imageRepoCommand = imageRepoCommand,
                        registryMetadata = registryMetadata
                    ),
                    imageRepoCommand.mappedTemplateVars + mapOf("digest" to digest)
                )
        }
    }

    private fun digestExistInRepo(
        imageRepoCommand: ImageRepoCommand,
        registryMetadata: RegistryMetadata,
        digest: String
    ): Boolean {

        return webClient
            .head()
            .uri(
                imageRegistryUrlBuilder.createBlobUrl(
                    imageRepoCommand = imageRepoCommand,
                    registryMetadata = registryMetadata
                ),
                imageRepoCommand.mappedTemplateVars + mapOf("digest" to digest)
            )
            .headers { headers ->
                imageRepoCommand.bearerToken?.let {
                    headers.set(AUTHORIZATION, "Basic $it")
                }
            }
            .retrieve()
            .onStatus({ it.value() == 404 }) { Mono.empty() }
            .bodyToMono<ByteArray>()
            .blockAndHandleError(imageRepoCommand = imageRepoCommand)
            ?.let { true } ?: false
    }

    private fun JsonNode.getV2Information(
        imageRepoCommand: ImageRepoCommand,
        registryMetadata: RegistryMetadata
    ): JsonNode {
        val configDigest = listOf(
            this.at("/config").get("digest").asText().replace(
                regex = "\\s".toRegex(),
                replacement = ""
            ).split(":").last()
        ).associate { "digest" to "sha256:$it" }

        return getBodyFromDockerRegistry(imageRepoCommand) { webClient ->
            webClient
                .get()
                .uri(
                    imageRegistryUrlBuilder.createBlobUrl(
                        imageRepoCommand = imageRepoCommand,
                        registryMetadata = registryMetadata
                    ),
                    imageRepoCommand.mappedTemplateVars + configDigest
                )
                .headers {
                    it.accept = listOf(MediaType.valueOf("application/json"))
                }
        } ?: throw SourceSystemException(
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
}

private fun JsonNode.getEnvironmentVariablesFromManifest() =
    this.at("/config/Env").associate {
        val (key, value) = it.asText().split("=")
        key to value
    }
