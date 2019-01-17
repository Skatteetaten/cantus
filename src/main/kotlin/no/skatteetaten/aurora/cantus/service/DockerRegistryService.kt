package no.skatteetaten.aurora.cantus.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.cantus.controller.BadRequestException
import no.skatteetaten.aurora.cantus.controller.blockNonNullAndHandleError
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.toMono
import java.util.HashSet

data class DockerRegistryTagResponse(val name: String, val tags: List<String>)
data class ManifestResponse(val contentType: String?, val dockerContentDigest: String?, val manifestBody: JsonNode?)

val logger = LoggerFactory.getLogger(DockerRegistryService::class.java)

@Service
class DockerRegistryService<T>(
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
        "NODE_VERSION"
    )

    val dockerVersionLabel = "docker_version"
    val dockerContentDigestLabel = "Docker-Content-Digest"
    val createdLabel = "created"

    fun getImageManifestInformation(
        imageAffiliation: String,
        imageName: String,
        imageTag: String,
        registryUrl: String? = null
    ): Map<String, String> {
        val url = registryUrl ?: dockerRegistryUrl

        validateDockerRegistryUrl(url, dockerRegistryUrlsAllowed)

        logger.debug("Retrieving manifest from $url")
        logger.debug("Retrieving image manifest with name $imageAffiliation/$imageName and tag $imageTag")
        val dockerResponse = getManifestFromRegistry { webClient ->
            webClient
                .get()
                .uri(
                    "$url/v2/{imageAffiliation}/{imageName}/manifests/{imageTag}",
                    imageAffiliation,
                    imageName,
                    imageTag
                )
                .headers {
                    it.accept = dockerManfestAccept
                }
        }
        
        val dockerContentDigest = dockerResponse.dockerContentDigest ?: return emptyMap()
        val contentType = dockerResponse.contentType ?: return emptyMap()
        val manifestBody =
            dockerResponse.manifestBody?.checkSchemaCompatibility(contentType, imageAffiliation, imageName) ?: return emptyMap()

        return extractManifestInformation(manifestBody, dockerContentDigest)
    }

    fun getImageTags(imageGroup: String, imageName: String, registryUrl: String? = null): List<String> {
        val url = registryUrl ?: dockerRegistryUrl

        validateDockerRegistryUrl(url, dockerRegistryUrlsAllowed)

        val tagsResponse: DockerRegistryTagResponse? = getBodyFromDockerRegistry {
            logger.debug("Retrieving tags from {url}/v2/{imageGroup}/{imageName}/tags/list", url, imageName)
            it
                .get()
                .uri("$url/v2/{imageGroup}/{imageName}/tags/list", imageGroup, imageName)
        }



        return tagsResponse?.tags ?: emptyList()
    }

    fun getImageTagsGroupedBySemanticVersion(
        imageGroup: String,
        imageName: String,
        registryUrl: String? = null
    ): Map<String, List<String>> {
        val tags = getImageTags(imageGroup, imageName, registryUrl)

        logger.debug("Tags are grouped by semantic version")
        return tags.groupBy { ImageTagType.typeOf(it).toString() }
    }

    private fun extractManifestInformation(
        manifestBody: JsonNode,
        dockerContentDigest: String
    ): Map<String, String> {
        val environmentVariables = manifestBody.getEnvironmentVariablesFromManifest()

        val imageManifestEnvInformation = environmentVariables
            .filter { manifestEnvLabels.contains(it.key) }
            .mapKeys { it.key.toUpperCase() }

        val dockerVersion = manifestBody.getVariableFromManifestBody(dockerVersionLabel)
        val created = manifestBody.getVariableFromManifestBody(createdLabel)

        val imageManifestConfigInformation = mapOf(
            dockerVersionLabel to dockerVersion,
            dockerContentDigestLabel to dockerContentDigest,
            createdLabel to created
        ).mapKeys { it.key.toUpperCase().replace("-", "_") }

        return imageManifestEnvInformation + imageManifestConfigInformation
    }

    private fun validateDockerRegistryUrl(urlToValidate: String, alllowedUrls: List<String>) {
        if (!alllowedUrls.any { allowedUrl: String -> urlToValidate == allowedUrl }) throw BadRequestException("Invalid Docker Registry URL")
    }

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
    ): ManifestResponse = fn(webClient)
        .exchange()
        .flatMap { resp ->
            if (resp.statusCode() == HttpStatus.NOT_FOUND) {
                ManifestResponse(null, null, null).toMono()
            } else {
                val contentType = resp.headers().contentType().get().toString()
                val dockerContentDigest = resp.headers().header(dockerContentDigestLabel).first()

                resp.bodyToMono<JsonNode>().map {
                    ManifestResponse(contentType, dockerContentDigest, it)
                }
            }
        }.blockNonNullAndHandleError(sourceSystem = "docker")

    private fun JsonNode.checkSchemaCompatibility(
        contentType: String,
        imageAffiliation: String,
        imageName: String
    ): JsonNode =
        when (contentType) {
            "application/vnd.docker.distribution.manifest.v2+json" ->
                this.getV2Information(imageAffiliation, imageName)
            else -> {
                this.getV1CompatibilityFromManifest()
            }
        }

    private fun JsonNode.getV2Information(imageAffiliation: String, imageName: String): JsonNode {
        val configDigest = this.at("/config").get("digest").asText().replace("\\s".toRegex(), "").split(":").last()
        return getBodyFromDockerRegistry { webClient ->
            webClient
                .get()
                .uri(
                    "$dockerRegistryUrl/v2/{imageGroup}/{imageName}/blobs/sha256:{configDigest}",
                    imageAffiliation,
                    imageName,
                    configDigest
                )
                .headers {
                    it.accept = listOf(MediaType.valueOf("application/json"))
                }
        }
    }
}

private fun JsonNode.getV1CompatibilityFromManifest() =
    jacksonObjectMapper().readTree(this.get("history")?.get(0)?.get("v1Compatibility")?.asText() ?: "")

private fun JsonNode.getEnvironmentVariablesFromManifest() =
    this.at("/config/Env").associate {
        val (key, value) = it.asText().split("=")
        key to value
    }

private fun JsonNode.getVariableFromManifestBody(label: String) = this.get(label)?.asText() ?: ""
