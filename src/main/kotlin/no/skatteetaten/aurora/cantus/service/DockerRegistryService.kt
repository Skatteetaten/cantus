package no.skatteetaten.aurora.cantus.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.cantus.extensions.asMap
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.RequestEntity
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.net.URI
import java.util.HashSet

@Service
class DockerRegistryService(val httpClient: RestTemplate) {

    val DOCKER_MANIFEST_V2: String = "application/vnd.docker.distribution.manifest.v2+json"

    val DOCKER_REGISTRY_URL_BODY = "https://test-docker-registry-internal-group.aurora.skead.no"
    val DOCKER_REGISTRY_URL_HEAD = "http://uil0paas-utv-registry01.skead.no:9090"

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

    val manifestVersionLabels: HashSet<String> = hashSetOf(
        "docker_version",
        "nodejs_version"
    )

    val manifestImageDigestLabel = "Docker-Content-Digest"

    fun getImageManifest(registryUrl: String?, imageName: String, imageTag: String): JsonNode? {
        val url = registryUrl ?: DOCKER_REGISTRY_URL_BODY

        val bodyRequest = createManifestRequest(url, imageName, imageTag)
        val responseBodyRequest = httpClient.exchange(bodyRequest, JsonNode::class.java)

        val jsonParser = ObjectMapper()
        val bodyOfManifest = jsonParser.readTree(responseBodyRequest.body["history"][0]["v1Compatibility"].asText())

        val env = bodyOfManifest.at("/config/Env").map {
            val (key, value) = it.asText().split("=")
            key to value
        }.toMap()

        val filteredEnv = env.filter { manifestEnvLabels.contains(it.key) }.mapKeys { it.key.toUpperCase() }
        val versions =
            bodyOfManifest.asMap().filter { manifestVersionLabels.contains(it.key) }.mapKeys { it.key.toUpperCase() }

        val headerRequest = createManifestRequest(DOCKER_REGISTRY_URL_HEAD, imageName, imageTag, DOCKER_MANIFEST_V2)
        val responseHeaderRequest = httpClient.exchange(headerRequest, JsonNode::class.java)

        val headOfManifest = mapOf(
            manifestImageDigestLabel.toUpperCase() to responseHeaderRequest.headers[manifestImageDigestLabel]?.get(0)
        )

        val manifest = headOfManifest.plus(versions).plus(filteredEnv)

        return jsonParser.readTree(jsonParser.writeValueAsString(manifest))
    }

    fun getImageTags(registryUrl: String?, imageName: String): JsonNode {
        val url = registryUrl ?: DOCKER_REGISTRY_URL_BODY

        val manifestUri = URI("$url/v2/$imageName/tags/list")
        val header = HttpHeaders()

        val tagsRequest = RequestEntity<JsonNode>(header, HttpMethod.GET, manifestUri)
        val response = httpClient.exchange(tagsRequest, JsonNode::class.java)

        val jsonParser = ObjectMapper()

        return jsonParser.readTree(jsonParser.writeValueAsString(response)).at("/body/tags")
    }

    fun getImageTagsGroupedBySemanticVersion(registryUrl: String?, imageName: String): JsonNode {
        val tags = getImageTags(registryUrl, imageName)
        val jsonParser = ObjectMapper()
        return jsonParser.convertValue(tags.groupBy { ImageTagType.typeOf(it.asText()) }, JsonNode::class.java)
    }

    private fun createManifestRequest(
        registryUrl: String,
        imageName: String,
        imageTag: String,
        headerAccept: String = ""
    ): RequestEntity<*> {
        val manifestUri = URI("$registryUrl/v2/$imageName/manifests/$imageTag")
        val header = HttpHeaders()
        if (headerAccept != "") header.accept = listOf(MediaType.valueOf(headerAccept))

        return RequestEntity<JsonNode>(header, HttpMethod.GET, manifestUri)
    }
}