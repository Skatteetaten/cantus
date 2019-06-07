package no.skatteetaten.aurora.cantus.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging
import no.skatteetaten.aurora.cantus.controller.BadRequestException
import no.skatteetaten.aurora.cantus.controller.ForbiddenException
import no.skatteetaten.aurora.cantus.controller.ImageRepoCommand
import no.skatteetaten.aurora.cantus.controller.SourceSystemException
import no.skatteetaten.aurora.cantus.controller.blockAndHandleError
import no.skatteetaten.aurora.cantus.controller.handleError
import no.skatteetaten.aurora.cantus.controller.handleStatusCodeError
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
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
    val locationHeader = "Location"
    val createdLabel = "created"

    /*
        https://www.danlorenc.com/posts/containers-part-2/
     */
    fun tagImage(from: ImageRepoCommand, to: ImageRepoCommand): Boolean {

        val (fromRegistry, manifest) = getImageManifest(from)

        val layers = findLayers(manifest)

        //TODO: do in paralell
        val results = layers.map {
            ensureLayerExist(from, to, it)
        }

        val toRegistryMetadata = registryMetadataResolver.getMetadataForRegistry(to.registry)
        return putManifest(to, toRegistryMetadata, manifest)
        //TODO: error handling
        //put manifest

        return true
    }

    //TODO better error handling
    private fun ensureLayerExist(from: ImageRepoCommand, to: ImageRepoCommand, digest: String): Boolean {

        val toRegistryMetadata = registryMetadataResolver.getMetadataForRegistry(to.registry)
        val fromRegistryMethod = registryMetadataResolver.getMetadataForRegistry(from.registry)

        if (digestExistInRepo(to, toRegistryMetadata, digest)) {
            return true
        }
        val result: Pair<String, JsonNode?> = createUploadUrl(to, toRegistryMetadata) { webClient ->

            webClient
                .post()
                .uri(
                    imageRegistryUrlBuilder.createUploadUrl(to, toRegistryMetadata),
                    to.mappedTemplateVars
                )

        } ?: return false
        val location = result.first

        val data = getLayer(from, fromRegistryMethod, digest) ?: return false

        return postLayer(to, toRegistryMetadata, "$location?digest=$digest", data)
    }
    private fun putManifest(
        to: ImageRepoCommand,
        toRegistryMetadata: RegistryMetadata,
        manifest: ImageManifestResponseDto
    ): Boolean {
        return getBodyFromDockerRegistry<JsonNode>(to, toRegistryMetadata) { webClient ->
            webClient
                .put()
                .uri(
                    imageRegistryUrlBuilder.createManifestUrl(to, toRegistryMetadata),
                    to.mappedTemplateVars
                )
                .headers {
                    it.contentType = MediaType.valueOf(manifest.contentType)
                }
                .body(BodyInserters.fromObject(manifest.manifestBody))
        }?.let { true } ?: false
    }

    private fun postLayer(
        to: ImageRepoCommand,
        toRegistryMetadata: RegistryMetadata,
        url: String,
        data: ByteArray
    ): Boolean {
        logger.debug("posting layer to url=$url")
        return getBodyFromDockerRegistry<JsonNode>(to, toRegistryMetadata) { webClient ->
            webClient
                .post()

                .uri(url)
                .body(BodyInserters.fromObject(data))
                .headers {
                    it.contentType = MediaType.APPLICATION_OCTET_STREAM
                }
        }?.let { true } ?: false
    }

    fun findLayers(manifest: ImageManifestResponseDto): List<String> {
        return if (manifest.contentType == manifestV2) {
            val layers: ArrayNode = manifest.manifestBody["layers"] as ArrayNode
            layers.map { it["digest"].textValue() }
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

        val dockerResponse = getManifestFromRegistry(imageRepoCommand, registryMetadata) { webClient ->
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
            getBodyFromDockerRegistry(imageRepoCommand, registryMetadata) { webClient ->
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
        registryMetadata: RegistryMetadata,
        fn: (WebClient) -> WebClient.RequestHeadersSpec<*>
    ): T? = fn(webClient)
        .headers {
            if (registryMetadata.authenticationMethod == AuthenticationMethod.KUBERNETES_TOKEN) {
                it.setBearerAuth(
                    imageRepoCommand.bearerToken
                        ?: throw ForbiddenException("Authorization bearer token is not present")
                )
            }
        }
        .retrieve()
        .bodyToMono<T>()
        .blockAndHandleError(imageRepoCommand = imageRepoCommand)

    private fun createUploadUrl(
        imageRepoCommand: ImageRepoCommand,
        registryMetadata: RegistryMetadata,
        fn: (WebClient) -> WebClient.RequestHeadersSpec<*>
    ): Pair<String, JsonNode>? = fn(webClient)
        .headers {
            if (registryMetadata.authenticationMethod == AuthenticationMethod.KUBERNETES_TOKEN) {
                it.setBearerAuth(
                    imageRepoCommand.bearerToken
                        ?: throw ForbiddenException("Authorization bearer token is not present")
                )
            }
        }
        .exchange()
        .flatMap { resp ->
            resp.handleStatusCodeError(imageRepoCommand.registry)

            val locationHeader = resp.headers().header(locationHeader).firstOrNull()
                ?: throw SourceSystemException(
                    message = "Response did not contain $locationHeader header",
                    sourceSystem = imageRepoCommand.registry
                )

            resp.bodyToMono<JsonNode>().map {
                locationHeader to it
            }
        }
        .handleError(imageRepoCommand)
        .block(Duration.ofSeconds(5))

    private fun getManifestFromRegistry(
        imageRepoCommand: ImageRepoCommand,
        registryMetadata: RegistryMetadata,
        fn: (WebClient) -> WebClient.RequestHeadersSpec<*>
    ): ImageManifestResponseDto? = fn(webClient)
        .headers {
            if (registryMetadata.authenticationMethod == AuthenticationMethod.KUBERNETES_TOKEN) {
                it.setBearerAuth(
                    imageRepoCommand.bearerToken
                        ?: throw ForbiddenException("Authorization bearer token is not present")
                )
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
            "application/vnd.docker.distribution.manifest.v2+json" ->
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
        return getBodyFromDockerRegistry(imageRepoCommand, registryMetadata) { webClient ->
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
        val body: JsonNode? = getBodyFromDockerRegistry(imageRepoCommand, registryMetadata) { webClient ->
            webClient
                .head()
                .uri(
                    imageRegistryUrlBuilder.createBlobUrl(
                        imageRepoCommand = imageRepoCommand,
                        registryMetadata = registryMetadata
                    ),
                    imageRepoCommand.mappedTemplateVars + mapOf("digest" to digest)
                )
                .headers {
                    it.accept = listOf(MediaType.valueOf("application/json"))
                }
        }
        return body?.let { true } ?: false
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

        return getBodyFromDockerRegistry(imageRepoCommand, registryMetadata) { webClient ->
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
