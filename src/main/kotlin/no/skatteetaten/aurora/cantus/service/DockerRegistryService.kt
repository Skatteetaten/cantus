package no.skatteetaten.aurora.cantus.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.cantus.controller.BadRequestException
import no.skatteetaten.aurora.cantus.controller.blockNonNullAndHandleError
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.toMono
import java.util.HashSet

val logger = LoggerFactory.getLogger(DockerRegistryService::class.java)

@Service
class DockerRegistryService(
    val webClient: WebClient,
    @Value("\${cantus.docker-registry-url}") val dockerRegistryUrl: String,
    @Value("\${cantus.docker-registry-url-allowed}") val dockerRegistryUrlsAllowed: List<String>
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
        "NODE_VERSION",
        "NGINX_VERSION"
    )

    val dockerVersionLabel = "docker_version"
    val dockerContentDigestLabel = "Docker-Content-Digest"
    val createdLabel = "created"

    fun getImageManifestInformation(
        imageGroup: String,
        imageName: String,
        imageTag: String,
        registryUrl: String? = null
    ): ImageManifestDto {
        val url = registryUrl ?: dockerRegistryUrl

        validateDockerRegistryUrl(url, dockerRegistryUrlsAllowed)

        logger.debug("Retrieving manifest from $url")
        logger.debug("Retrieving image manifest with name $imageGroup/$imageName and tag $imageTag")
        val dockerResponse = getManifestFromRegistry { webClient ->
            webClient
                .get()
                .uri(
                    "$url/v2/{imageGroup}/{imageName}/manifests/{imageTag}",
                    imageGroup,
                    imageName,
                    imageTag
                )
                .headers {
                    it.accept = dockerManfestAccept
                }
        }

        val imageManifest = imageManifestResponseToImageManifest(imageGroup, imageName, dockerResponse)
        return imageManifest
    }

    fun getImageTags(imageGroup: String, imageName: String, registryUrl: String? = null): ImageTagsWithTypeDto {
        val url = registryUrl ?: dockerRegistryUrl

        validateDockerRegistryUrl(url, dockerRegistryUrlsAllowed)

        val tagsResponse: ImageTagsResponseDto = getBodyFromDockerRegistry {
            logger.debug("Retrieving tags from {url}/v2/{imageGroup}/{imageName}/tags/list", url, imageGroup, imageName)
            it
                .get()
                .uri("$url/v2/{imageGroup}/{imageName}/tags/list", imageGroup, imageName)
        }

        return ImageTagsWithTypeDto(tags = tagsResponse.tags.map {
            ImageTagTypedDto(it)
        })
    }
/*

    fun getImageTagsGroupedBySemanticVersion(
        imageGroup: String,
        imageName: String,
        registryUrl: String? = null
    ): Map<ImageTagType, List<TagResource>> {
        val tags = getImageTags(imageGroup, imageName, registryUrl)

        logger.debug("Tags are grouped by semantic version")

        return tags.items.groupBy {
            it.type
        }
    }
*/

    private final inline fun <reified T : Any> getBodyFromDockerRegistry(
        fn: (WebClient) -> WebClient.RequestHeadersSpec<*>
    ): T = fn(webClient)
        .exchange()
        .flatMap { resp ->
            resp.bodyToMono(T::class.java)
        }
        .blockNonNullAndHandleError(sourceSystem = "docker")

    private fun getManifestFromRegistry(
        fn: (WebClient) -> WebClient.RequestHeadersSpec<*>
    ): ImageManifestResponseDto = fn(webClient)
        .exchange()
        .flatMap { resp ->
            val statusCode = resp.statusCode().value()
            if (statusCode == 404) {
                ImageManifestResponseDto(statusCode = statusCode).toMono()
            } else {
                val contentType = resp.headers().contentType().get().toString()
                val dockerContentDigest = resp.headers().header(dockerContentDigestLabel).first()

                resp.bodyToMono<JsonNode>().map {
                    ImageManifestResponseDto(contentType, dockerContentDigest, it, statusCode)
                }
            }
        }.blockNonNullAndHandleError(sourceSystem = "docker")

    private fun imageManifestResponseToImageManifest(
        imageGroup: String,
        imageName: String,
        imageManifestResponse: ImageManifestResponseDto
    ): ImageManifestDto {
        if (imageManifestResponse.statusCode == 404 ||
            imageManifestResponse.contentType == null ||
            imageManifestResponse.dockerContentDigest == null ||
            imageManifestResponse.manifestBody == null){
            return ImageManifestDto(dockerDigest = "", dockerVersion = "")
        }

        val manifestBody = imageManifestResponse
            .manifestBody.checkSchemaCompatibility(imageManifestResponse.contentType, imageGroup, imageName)

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
            javaVersionMajor = imageManifestEnvInformation["JAVA_VERSION_MAJOR"],
            javaVersionMinor = imageManifestEnvInformation["JAVA_VERSION_MINOR"],
            javaVersionBuild = imageManifestEnvInformation["JAVA_VERSION_BUILD"]
        )
    }

    private fun JsonNode.checkSchemaCompatibility(
        contentType: String,
        imageGroup: String,
        imageName: String
    ): JsonNode =
        when (contentType) {
            "application/vnd.docker.distribution.manifest.v2+json" ->
                this.getV2Information(imageGroup, imageName)
            else -> {
                this.getV1CompatibilityFromManifest()
            }
        }

    private fun JsonNode.getV2Information(imageGroup: String, imageName: String): JsonNode {
        val configDigest = this.at("/config").get("digest").asText().replace("\\s".toRegex(), "").split(":").last()
        return getBodyFromDockerRegistry { webClient ->
            webClient
                .get()
                .uri(
                    "$dockerRegistryUrl/v2/{imageGroup}/{imageName}/blobs/sha256:{configDigest}",
                    imageGroup,
                    imageName,
                    configDigest
                )
                .headers {
                    it.accept = listOf(MediaType.valueOf("application/json"))
                }
        }
    }
    private fun JsonNode.getV1CompatibilityFromManifest() =
        jacksonObjectMapper().readTree(this.get("history")?.get(0)?.get("v1Compatibility")?.asText() ?: "")

    private fun JsonNode.getVariableFromManifestBody(label: String) = this.get(label)?.asText() ?: ""

    private fun validateDockerRegistryUrl(urlToValidate: String, alllowedUrls: List<String>) {
        if (!alllowedUrls.any { allowedUrl: String -> urlToValidate == allowedUrl }) throw BadRequestException("Invalid Docker Registry URL")
    }
}



private fun JsonNode.getEnvironmentVariablesFromManifest() =
    this.at("/config/Env").associate {
        val (key, value) = it.asText().split("=")
        key to value
    }


