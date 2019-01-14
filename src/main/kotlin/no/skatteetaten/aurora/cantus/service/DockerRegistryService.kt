package no.skatteetaten.aurora.cantus.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.cantus.controller.BadRequestException
import no.skatteetaten.aurora.cantus.controller.DockerRegistryException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import java.util.HashSet
import java.util.function.Function
import java.util.function.Predicate

data class DockerRegistryTagResponse(val name: String, val tags: List<String>)

val logger = LoggerFactory.getLogger(DockerRegistryService::class.java)

@Service
class DockerRegistryService(
    val webClient: WebClient,
    @Value("\${cantus.docker-registry-url}") val dockerRegistryUrl: String,
    @Value("\${cantus.docker-registry-url-allowed}") val dockerRegistryUrlsAllowed: List<String>
) {
    val DOCKER_MANIFEST_ACCEPT: List<MediaType> = listOf(
        MediaType.valueOf("application/vnd.docker.distribution.manifest.v2+json"),
        MediaType.valueOf("application/vnd.docker.distribution.manifest.v1+json"),
        MediaType.valueOf("application/json")
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
        imageName: String,
        imageTag: String,
        registryUrl: String? = null
    ): Map<String, String> {
        val url = registryUrl ?: dockerRegistryUrl

        validateDockerRegistryUrl(url, dockerRegistryUrlsAllowed)

        logger.debug("Retrieving manifest from $url")
        logger.debug("Trying to get image with name $imageName and tag $imageTag")
        val manifestUri = createManifestRequest(url, imageName, imageTag)
        val dockerResponse = getManifestFromRegistry(manifestUri)
        val dockerContentDigest = dockerResponse.second ?: throw DockerRegistryException("Error from Docker Registry")
        val manifestBody = dockerResponse.first?.block() ?: throw DockerRegistryException("Error from Docker Registry")

        return extractManifestInformation(manifestBody, dockerContentDigest)
    }

    fun getImageTags(imageName: String, registryUrl: String? = null): List<String> {
        val url = registryUrl ?: dockerRegistryUrl

        validateDockerRegistryUrl(url, dockerRegistryUrlsAllowed)

        val tagsUrl = ("$url/v2/$imageName/tags/list")

        logger.debug("Retrieving tags from {}", tagsUrl)
        val tagsResponse: DockerRegistryTagResponse? = getBodyFromDockerRegistry(tagsUrl)

        return tagsResponse?.tags ?: listOf()
    }

    fun getImageTagsGroupedBySemanticVersion(
        imageName: String,
        registryUrl: String? = null
    ): Map<String, List<String>> {
        val tags = getImageTags(imageName, registryUrl)

        logger.debug("Tags are grouped by semantic version")
        return tags.groupBy { ImageTagType.typeOf(it).toString() }
    }

    private fun createManifestRequest(
        registryUrl: String,
        imageName: String,
        imageTag: String
    ) = "$registryUrl/v2/$imageName/manifests/$imageTag"

    private fun extractManifestInformation(
        manifestBody: JsonNode,
        dockerContentDigest: String
    ): Map<String, String> {

        val v1Compatibility = manifestBody.getV1CompatibilityFromManifest()

        val environmentVariables = v1Compatibility.getEnvironmentVariablesFromManifest()

        val imageManifestEnvInformation = environmentVariables
            .filter { manifestEnvLabels.contains(it.key) }
            .mapKeys { it.key.toUpperCase() }

        val dockerVersion = v1Compatibility.getVariableFromManifestBody(dockerVersionLabel)
        val created = v1Compatibility.getVariableFromManifestBody(createdLabel)

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
        apiUrl: String
    ): T? = webClient
        .get()
        .uri(apiUrl)
        .retrieve()
        .onStatus(Predicate.isEqual<HttpStatus>(HttpStatus.NOT_FOUND), Function { Mono.empty() })
        .bodyToMono<T>()
        .block()

    private fun ClientResponse.getBodyAsJson(): JsonNode? = this.bodyToMono(JsonNode::class.java).block()

    private fun getManifestFromRegistry(
        apiUrl: String
    ): Pair<Mono<JsonNode>?, String?> {
        val clientResponse =
            webClient
                .get()
                .uri(apiUrl)
                .headers {
                    it.accept = DOCKER_MANIFEST_ACCEPT
                }
                .exchange()
                .block()
                ?.checkResponseStatusCode()
                ?: throw DockerRegistryException("Error in response from docker registry")

        val bodyOfManifest = clientResponse.bodyToMono<JsonNode>()
        val headerOfManifest = clientResponse.headers().header(dockerContentDigestLabel)
        return bodyOfManifest to headerOfManifest[0]
    }
}

private fun ClientResponse.checkResponseStatusCode(): ClientResponse {
    when (this.statusCode()) {
        HttpStatus.NOT_FOUND -> throw DockerRegistryException("Image information in docker registry not found")
        HttpStatus.INTERNAL_SERVER_ERROR -> throw DockerRegistryException("Error from docker registry")
        HttpStatus.BAD_REQUEST -> throw DockerRegistryException("Error in request sent to docker registry")
        else -> {
            return this
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
