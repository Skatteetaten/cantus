package no.skatteetaten.aurora.cantus.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.cantus.controller.DockerRegistryException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.RequestEntity
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.RestTemplate
import java.net.URI
import java.util.*
import kotlin.reflect.KClass

data class DockerRegistryTagResponse(val name: String, val tags: List<String>)


@Service
class DockerRegistryService(val restTemplate: RestTemplate,
                            @Value("\${cantus.docker-registry-url-body}") val dockerRegistryUrlBody: String,
                            @Value("\${cantus.docker-registry-url-header}") val dockerRegistryUrlHeader: String) {

    val DOCKER_MANIFEST_V2: String = "application/vnd.docker.distribution.manifest.v2+json"
    val logger = LoggerFactory.getLogger(DockerRegistryService::class.java)

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

    fun getImageManifestAndExtractInformation(imageName: String, imageTag: String, registryUrl: String? = null): Map<String, String> {
        val url = registryUrl ?: dockerRegistryUrlBody

        val bodyRequest = createManifestRequest(url, imageName, imageTag)
        val headerRequest = createManifestRequest(dockerRegistryUrlHeader, imageName, imageTag, DOCKER_MANIFEST_V2)

        logger.debug("Henter ut manifest fra $url")
        val responseBodyRequest = restTemplate.exchangeAndLogError(bodyRequest, JsonNode::class)

        val bodyOfManifest = ObjectMapper().readTree(responseBodyRequest.body?.get("history")?.get(0)?.get("v1Compatibility")?.asText()
                ?: return mapOf())

        val env = bodyOfManifest.at("/config/Env").map {
            val (key, value) = it.asText().split("=")
            key to value
        }.toMap()
        val imageManifestEnvInformation = env
                .filter { manifestEnvLabels.contains(it.key) }
                .mapKeys { it.key.toUpperCase() }

        logger.debug("Henter ut manifest fra $dockerRegistryUrlHeader")
        val responseHeaderRequest = restTemplate.exchangeAndLogError(headerRequest, JsonNode::class)

        val dockerVersion = bodyOfManifest.get(dockerVersionLabel)?.asText() ?: ""
        val dockerContentDigest = responseHeaderRequest.headers[dockerContentDigestLabel]?.get(0) ?: ""

        val imageManifestConfigInformation = mapOf(
                dockerVersionLabel to dockerVersion,
                dockerContentDigestLabel to dockerContentDigest)
                .mapKeys { it.key.toUpperCase() }

        return imageManifestEnvInformation + imageManifestConfigInformation
    }

    fun getImageTags(imageName: String, registryUrl: String? = null): List<String> {
        val url = registryUrl ?: dockerRegistryUrlBody

        val manifestUri = URI("$url/v2/$imageName/tags/list")
        val header = HttpHeaders()

        logger.debug("Henter tags fra {}", manifestUri)
        val tagsRequest = RequestEntity<JsonNode>(header, HttpMethod.GET, manifestUri)
        val response = restTemplate.exchangeAndLogError(tagsRequest, DockerRegistryTagResponse::class)
        return response.body?.tags ?: listOf()
    }

    fun getImageTagsGroupedBySemanticVersion(imageName: String, registryUrl: String? = null): Map<String, List<String>> {
        val url = registryUrl ?: dockerRegistryUrlBody
        val tags = getImageTags(imageName, url)

        logger.debug("Grupperer tags etter semantisk versjon")
        return tags.groupBy { ImageTagType.typeOf(it).toString() }
    }

    private fun createManifestRequest(
            registryUrl: String,
            imageName: String,
            imageTag: String,
            headerAccept: String = ""
    ): RequestEntity<JsonNode> {
        val manifestUri = URI("$registryUrl/v2/$imageName/manifests/$imageTag")
        val header = HttpHeaders()
        if (headerAccept != "") header.accept = listOf(MediaType.valueOf(headerAccept))

        return RequestEntity(header, HttpMethod.GET, manifestUri)
    }

}


private fun <T : Any> RestTemplate.exchangeAndLogError(request: RequestEntity<JsonNode>, returnType: KClass<T>) =
        try {
            this.exchange(request, returnType.java)
        } catch (e: RestClientResponseException) {
            LoggerFactory.getLogger(DockerRegistryService::class.java).warn("Feil fra Docker Registry ${request.url} status: ${e.rawStatusCode} - ${e.statusText}")
            throw DockerRegistryException("Feil fra Docker Registry status: ${e.rawStatusCode} - ${e.statusText}")
        }
