package no.skatteetaten.aurora.cantus.controller

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

// TODO: burde dette være en DTO eller en domain model?
data class ImageRepoCommand(
    val registry: String,
    val port: Int? = null,
    val imageGroup: String,
    val imageName: String,
    val imageTag: String? = null,
    val bearerToken: String? = null
) {
    val manifestRepo: String
        get() = listOf(imageGroup, imageName, imageTag).joinToString("/")
    val defaultRepo: String
        get() = listOf(imageGroup, imageName).joinToString("/")
    val mappedTemplateVars: Map<String, String?>
        get() = mapOf(
            "imageGroup" to imageGroup,
            "imageName" to imageName,
            "imageTag" to imageTag
        )
}

@Component
class ImageRepoDtoAssembler(
    @Value("\${cantus.docker.url}") val registryUrl: String,
    @Value("\${cantus.docker.urlsallowed}") val allowedRegistryUrls: List<String>
) {
    fun createAndValidateCommand(
        overrideRegistryUrl: String?,
        name: String,
        namespace: String,
        tag: String? = null,
        bearerToken: String? = null
    ): ImageRepoCommand {
        val validatedRegistryUrl = if (overrideRegistryUrl != null) {
            validateDockerRegistryUrl(
                urlToValidate = overrideRegistryUrl,
                allowedUrls = allowedRegistryUrls
            )
        } else registryUrl

        return ImageRepoCommand(
            registry = validatedRegistryUrl,
            imageGroup = namespace,
            imageName = name,
            imageTag = tag,
            bearerToken = bearerToken
        )
    }

    private fun validateDockerRegistryUrl(urlToValidate: String, allowedUrls: List<String>): String {
        if (!allowedUrls.any { allowedUrl -> urlToValidate == allowedUrl }) {
            throw BadRequestException("Invalid Docker Registry URL")
        } else {
            return urlToValidate
        }
    }
}