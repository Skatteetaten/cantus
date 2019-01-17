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
import uk.q3c.rest.hal.HalResource
import java.time.Instant
import java.util.HashSet

import reactor.core.publisher.toMono

data class DockerRegistryTagResponse(val name: String, val tags: List<String>)
data class ManifestResponse(val contentType: String, val dockerContentDigest: String, val manifestBody: JsonNode, val statusCode: Int)

val logger = LoggerFactory.getLogger(DockerRegistryService::class.java)

data class TagResource(val name: String, val type: ImageTagType) : HalResource()

data class ImageTagResource(
    val auroraVersion: String?,
    val appVersion:String?,
    val timeline: Map<String, Instant> = emptyMap(),
    val dockerVersion: String,
    val dockerDigest: String
) : HalResource()

data class JavaImage(
    val major: String,
    val minor:String,
    val build:String,
    val jolokia: String?
): HalResource()


data class NodeImage(
    val nodeVersion: String,
    val nginxVersion:String
): HalResource()

data class AuroraResponse<T : HalResource>(
    val items: List<T> = emptyList(),
    val success: Boolean = true,
    val message: String = "OK",
    val exception: Throwable? = null,
    val count: Int = items.size
) : HalResource()

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
        "NODE_VERSION"
    )

    val dockerVersionLabel = "docker_version"
    val dockerContentDigestLabel = "Docker-Content-Digest"
    val createdLabel = "created"

    fun getImageManifestInformation(
        imageGroup: String,
        imageName: String,
        imageTag: String,
        registryUrl: String? = null
    ): ImageTagResource {
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

        if (dockerResponse.statusCode == 404) logger.debug("Empty list here")

        val dockerContentDigest = dockerResponse.dockerContentDigest
        val contentType = dockerResponse.contentType
        val manifestBody = dockerResponse
            .manifestBody.checkSchemaCompatibility(contentType, imageGroup, imageName)

        val manifestInformationMap = extractManifestInformation(manifestBody, dockerContentDigest)

        return ImageTagResource(
            auroraVersion = manifestInformationMap["AURORA_VERSION"],
            appVersion = manifestInformationMap["APP_VERSION"],
            timeline = mapOf(
                "BUILD_STARTED" to Instant.parse(manifestInformationMap["IMAGE_BUILD_TIME"]),
                "BUILD_DONE" to Instant.parse(manifestInformationMap["CREATED"])
            ),
            dockerDigest = manifestInformationMap["DOCKER_CONTENT_DIGEST"]!!,
            dockerVersion = manifestInformationMap["DOCKER_VERSION"]!!
        ).let {

            if (manifestInformationMap.containsKey("JAVA_VERSION_MAJOR")) {
                it.embed(
                    "java", JavaImage(
                        major = manifestInformationMap["JAVA_VERSION_MAJOR"]!!,
                        minor = manifestInformationMap["JAVA_VERSION_MINORl"]!!,
                        build = manifestInformationMap["JAVA_VERSION_BUILD"]!!,
                        jolokia = manifestInformationMap["JOLOKIA_VERSION"]!!
                    )
                )
            } else if (manifestInformationMap.containsKey("NODE_VERSION")) {
                it.embed(
                    "node", NodeImage(
                        nodeVersion = manifestInformationMap["NODE_VERSION"]!!,
                        nginxVersion = ""
                    )
                )
            }
            it
        }
    }

    fun getImageTags(imageGroup: String, imageName: String, registryUrl: String? = null): AuroraResponse<TagResource> {
        val url = registryUrl ?: dockerRegistryUrl

        validateDockerRegistryUrl(url, dockerRegistryUrlsAllowed)

        val tagsResponse: DockerRegistryTagResponse = getBodyFromDockerRegistry {
            logger.debug("Retrieving tags from {url}/v2/{imageGroup}/{imageName}/tags/list", url, imageGroup, imageName)
            it
                .get()
                .uri("$url/v2/{imageGroup}/{imageName}/tags/list", imageGroup, imageName)
        }

        if (tagsResponse == null) {
            return AuroraResponse(message = "Fikk ingen svar for...")
        }

        return AuroraResponse(tagsResponse.tags.map { TagResource(it, ImageTagType.typeOf(it)) })
    }

    fun getImageTagsGroupedBySemanticVersion(
        imageGroup: String,
        imageName: String,
        registryUrl: String? = null
    ): Map<String, List<String>> {
        val tags = getImageTags(imageGroup, imageName, registryUrl)

        logger.debug("Tags are grouped by semantic version")
        return tags.items.groupBy {
            it.type.toString()
        }
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
            val statusCode = resp.statusCode().value()
            if (statusCode == 404) {
                ManifestResponse("", "", jacksonObjectMapper().createObjectNode(), statusCode).toMono()
            } else {
                val contentType = resp.headers().contentType().get().toString()
                val dockerContentDigest = resp.headers().header(dockerContentDigestLabel).first()

                resp.bodyToMono<JsonNode>().map {
                    ManifestResponse(contentType, dockerContentDigest, it, statusCode)
                }
            }
        }.blockNonNullAndHandleError(sourceSystem = "docker")

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
}

private fun JsonNode.getV1CompatibilityFromManifest() =
    jacksonObjectMapper().readTree(this.get("history")?.get(0)?.get("v1Compatibility")?.asText() ?: "")

private fun JsonNode.getEnvironmentVariablesFromManifest() =
    this.at("/config/Env").associate {
        val (key, value) = it.asText().split("=")
        key to value
    }

private fun JsonNode.getVariableFromManifestBody(label: String) = this.get(label)?.asText() ?: ""
