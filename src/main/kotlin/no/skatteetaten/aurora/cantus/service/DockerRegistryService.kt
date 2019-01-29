package no.skatteetaten.aurora.cantus.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.cantus.controller.BadRequestException
import no.skatteetaten.aurora.cantus.controller.SourceSystemException
import no.skatteetaten.aurora.cantus.controller.blockAndHandleError
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import java.util.HashSet

val logger = LoggerFactory.getLogger(DockerRegistryService::class.java)

@Service
class DockerRegistryService(
    val webClient: WebClient,
    val registryMetadataResolver: RegistryMetadataResolver
) {
    val dockerManfestAccept: List<MediaType> = listOf(
        MediaType.valueOf("application/vnd.docker.distribution.manifest.v2+json"),
        MediaType.valueOf("application/vnd.docker.distribution.manifest.v1+json")
    )

    val manifestEnvLabels: HashSet<String> = hashSetOf(
        "AURORA_VERSION",
        "IMAGE_BUILD_TIME",
        "APP_VERSION",
        "JOLOKIA_VERSION",
        "JAVA_VERSION_MAJOR",
        "JAVA_VERSION_MINOR",
        "JAVA_VERSION_BUILD",
        "NODE_VERSION"
    )

    val dockerVersionLabel = "docker_version"
    val dockerContentDigestLabel = "Docker-Content-Digest"
    val createdLabel = "created"

    fun getImageManifestInformation(
        imageRepoDto: ImageRepoDto
    ): ImageManifestDto {
        val url = imageRepoDto.registry

        val registryMetadata = registryMetadataResolver.getMetadataForRegistry(url)

        val dockerResponse = getManifestFromRegistry(imageRepoDto, registryMetadata) { webClient ->
            webClient
                .get()
                .uri { uriBuilder ->
                    uriBuilder.createManifestUrl(imageRepoDto, registryMetadata)
                }
                .headers {
                    it.accept = dockerManfestAccept
                }
        } ?: throw SourceSystemException(
            "Manifest not found for image ${imageRepoDto.manifestRepo}",
            code = "404",
            sourceSystem = url
        )

        return imageManifestResponseToImageManifest(
            imageRepoDto = imageRepoDto,
            imageManifestResponse = dockerResponse,
            imageRegistryMetadata = registryMetadata
        )
    }

    fun getImageTags(imageRepoDto: ImageRepoDto): ImageTagsWithTypeDto {
        val url = imageRepoDto.registry

        val registryMetadata = registryMetadataResolver.getMetadataForRegistry(url)

        val tagsResponse: ImageTagsResponseDto? =
            getBodyFromDockerRegistry(imageRepoDto, registryMetadata) { webClient ->
                logger.debug("Retrieving tags from $url")
                webClient
                    .get()
                    .uri { uriBuilder ->
                        uriBuilder.createTagsUrl(imageRepoDto, registryMetadata)
                    }
            }

        if (tagsResponse == null || tagsResponse.tags.isEmpty()) {
            throw SourceSystemException(
                message = "Tags not found for image ${imageRepoDto.defaultRepo}",
                code = "404",
                sourceSystem = url
            )
        }

        return ImageTagsWithTypeDto(tags = tagsResponse.tags.map {
            ImageTagTypedDto(it)
        })
    }

    private final inline fun <reified T : Any> getBodyFromDockerRegistry(
        imageRepoDto: ImageRepoDto,
        registryMetadata: RegistryMetadata,
        fn: (WebClient) -> WebClient.RequestHeadersSpec<*>
    ): T? = fn(webClient)
        .headers {
            if (registryMetadata.authenticationMethod == AuthenticationMethod.KUBERNETES_TOKEN) {
                it.setBearerAuth(
                    imageRepoDto.bearerToken
                        ?: throw BadRequestException(message = "Authorization bearer token is not present")
                )
            }
        }
        .exchange()
        .flatMap { resp ->
            resp.bodyToMono(T::class.java)
        }
        .blockAndHandleError(sourceSystem = imageRepoDto.registry)

    private fun getManifestFromRegistry(
        imageRepoDto: ImageRepoDto,
        registryMetadata: RegistryMetadata,
        fn: (WebClient) -> WebClient.RequestHeadersSpec<*>
    ): ImageManifestResponseDto? = fn(webClient)
        .headers {
            if (registryMetadata.authenticationMethod == AuthenticationMethod.KUBERNETES_TOKEN) {
                it.setBearerAuth(
                    imageRepoDto.bearerToken
                        ?: throw BadRequestException(message = "Authorization bearer token is not present")
                )
            }
        }
        .exchange()
        .flatMap { resp ->
            val statusCode = resp.rawStatusCode()

            if (statusCode == 404) {
                resp.bodyToMono<JsonNode>() // Release resource
                Mono.empty<ImageManifestResponseDto>()
            } else {
                val contentType = resp.headers().contentType().get().toString()
                val dockerContentDigest = resp.headers().header(dockerContentDigestLabel).first()

                resp.bodyToMono<JsonNode>().map {
                    ImageManifestResponseDto(contentType, dockerContentDigest, it)
                }
            }
        }.blockAndHandleError(sourceSystem = imageRepoDto.registry)

    private fun imageManifestResponseToImageManifest(
        imageRepoDto: ImageRepoDto,
        imageManifestResponse: ImageManifestResponseDto,
        imageRegistryMetadata: RegistryMetadata
    ): ImageManifestDto {

        val manifestBody = imageManifestResponse
            .manifestBody.checkSchemaCompatibility(
            contentType = imageManifestResponse.contentType,
            imageRepoDto = imageRepoDto,
            imageRegistryMetadata = imageRegistryMetadata
        )

        val environmentVariables = manifestBody.getEnvironmentVariablesFromManifest()

        val imageManifestEnvInformation = environmentVariables
            .filter { manifestEnvLabels.contains(it.key) }
            .mapKeys { it.key.toUpperCase() }

        val dockerVersion = manifestBody.getVariableFromManifestBody(dockerVersionLabel)
        val created = manifestBody.getVariableFromManifestBody(createdLabel)

        return ImageManifestDto(
            dockerVersion = dockerVersion,
            dockerDigest = imageManifestResponse.dockerContentDigest,
            buildEnded = created,
            auroraVersion = imageManifestEnvInformation["AURORA_VERSION"],
            nodeVersion = imageManifestEnvInformation["NODE_VERSION"],
            appVersion = imageManifestEnvInformation["APP_VERSION"],
            buildStarted = imageManifestEnvInformation["IMAGE_BUILD_TIME"],
            java = JavaImageDto.fromEnvMap(imageManifestEnvInformation),
            jolokiaVersion = imageManifestEnvInformation["JOLOKIA_VERSION"]
        )
    }

    private fun JsonNode.checkSchemaCompatibility(
        contentType: String,
        imageRepoDto: ImageRepoDto,
        imageRegistryMetadata: RegistryMetadata
    ): JsonNode =
        when (contentType) {
            "application/vnd.docker.distribution.manifest.v2+json" ->
                this.getV2Information(imageRepoDto, imageRegistryMetadata)
            else -> {
                this.getV1CompatibilityFromManifest()
            }
        }

    private fun JsonNode.getV2Information(imageRepoDto: ImageRepoDto, registryMetadata: RegistryMetadata): JsonNode {
        val configDigest = this.at("/config").get("digest").asText().replace("\\s".toRegex(), "").split(":").last()

        return getBodyFromDockerRegistry(imageRepoDto, registryMetadata) { webClient ->
            webClient
                .get()
                .uri { uriBuilder ->
                    uriBuilder.createConfigUrl(imageRepoDto, configDigest, registryMetadata)
                }
                .headers {
                    it.accept = listOf(MediaType.valueOf("application/json"))
                }
        } ?: throw SourceSystemException(
            message = "Unable to retrieve V2 manifest for ${imageRepoDto.defaultRepo}/sha256:$configDigest",
            code = "404",
            sourceSystem = imageRepoDto.registry
        )
    }

    private fun JsonNode.getV1CompatibilityFromManifest() =
        jacksonObjectMapper().readTree(this.get("history")?.get(0)?.get("v1Compatibility")?.asText() ?: "")

    private fun JsonNode.getVariableFromManifestBody(label: String) = this.get(label)?.asText() ?: ""
}

private fun JsonNode.getEnvironmentVariablesFromManifest() =
    this.at("/config/Env").associate {
        val (key, value) = it.asText().split("=")
        key to value
    }