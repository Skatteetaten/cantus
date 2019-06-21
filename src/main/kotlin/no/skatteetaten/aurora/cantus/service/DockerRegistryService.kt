package no.skatteetaten.aurora.cantus.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import mu.KotlinLogging
import no.skatteetaten.aurora.cantus.controller.ImageRepoCommand
import no.skatteetaten.aurora.cantus.controller.SourceSystemException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.util.HashSet

private val logger = KotlinLogging.logger {}

//TODO: Extract all http reelvant code to DockerHttpClient
@Service
class DockerRegistryService(
    val httpClient: DockerHttpClient,
    val threadPoolContext: ExecutorCoroutineDispatcher
) {

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
    val createdLabel = "created"

    /*
    TODO: test with v1
        https://www.danlorenc.com/posts/containers-part-2/
     */
    fun tagImage(from: ImageRepoCommand, to: ImageRepoCommand): Boolean {

        val manifest = httpClient.getImageManifest(from)
        val layers = findBlobs(manifest)

        runBlocking(threadPoolContext + MDCContext()) {
            layers.map { digest ->
                async {
                    ensureBlobExist(from, to, digest).also {
                        logger.debug("Blob=$digest pushed to=${to.defaultRepo} success=$it")
                    }
                }
            }.forEach { it.await() }
        }
        return httpClient.putManifest(to, manifest).also {
            logger.debug("Manifest=$manifest pushed to=${to.fullRepoCommand}")
        }
    }

    fun ensureBlobExist(from: ImageRepoCommand, to: ImageRepoCommand, digest: String): Boolean {

        if (httpClient.digestExistInRepo(to, digest)) {
            logger.debug("layer=$digest already exist in registry=${to.defaultRepo}")
            return true
        }

        val uuid = httpClient.generateLocationUrl(to)
        //TODO: I think we need to throw exception if blob does not exist
        val data: ByteArray = httpClient.getBlob(from, digest) ?: return false

        return httpClient.postLayer(to, uuid, digest, data)
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

    //TODO: Not sure that putting this on JsonNode is the right way?
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

        return httpClient.getLayer(imageRepoCommand, configDigest)
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
        val dockerResponse = httpClient.getImageManifest(imageRepoCommand)

        return imageManifestResponseToImageManifest(
            imageRepoCommand = imageRepoCommand,
            imageManifestResponse = dockerResponse
        )
    }

    fun getImageTags(imageRepoCommand: ImageRepoCommand): ImageTagsWithTypeDto {

        val url = imageRepoCommand.registry
        val tagsResponse = httpClient.getImageTags(imageRepoCommand)

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
}

private fun JsonNode.getEnvironmentVariablesFromManifest() =
    this.at("/config/Env").associate {
        val (key, value) = it.asText().split("=")
        key to value
    }
