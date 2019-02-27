package no.skatteetaten.aurora.cantus.controller

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

data class ImageRepoCommand(
    val registry: String,
    val imageGroup: String,
    val imageName: String,
    val imageTag: String? = null,
    val bearerToken: String? = null
) {
    val manifestRepo: String
        get() = listOf(imageGroup, imageName, imageTag).joinToString("/")
    val defaultRepo: String
        get() = listOf(imageGroup, imageName).joinToString("/")
    val fullRepoCommand: String
        get() = listOf(registry, imageGroup, imageName, imageTag).joinToString("/")
    val mappedTemplateVars: Map<String, String?>
        get() = mapOf(
            "imageGroup" to imageGroup,
            "imageName" to imageName,
            "imageTag" to imageTag
        )
}

@Component
class ImageRepoCommandAssembler(
    @Value("\${cantus.docker.url}") val registryUrl: String,
    @Value("\${cantus.docker.urlsallowed}") val allowedRegistryUrls: List<String>
) {
    fun createAndValidateCommand(
        url: String,
        bearerToken: String? = null
    ): ImageRepoCommand? {
        val (overrideRegistryUrl, namespace, name, tag) = url.splitCheckNull()

        if (namespace.isNullOrEmpty() || name.isNullOrEmpty()) return null

        val validatedRegistryUrl =
            if (overrideRegistryUrl.isNullOrEmpty()) registryUrl
            else {
                validateDockerRegistryUrl(
                    urlToValidate = overrideRegistryUrl,
                    allowedUrls = allowedRegistryUrls
                )
            }

        return ImageRepoCommand(
            registry = validatedRegistryUrl,
            imageName = name,
            imageGroup = namespace,
            imageTag = tag,
            bearerToken = bearerToken
        )
    }

    private fun validateDockerRegistryUrl(urlToValidate: String, allowedUrls: List<String>): String {
        if (allowedUrls.any { allowedUrl -> urlToValidate == allowedUrl }) {
            return urlToValidate
        } else {
            throw BadRequestException("Invalid Docker Registry URL url=$urlToValidate")
        }
    }

    private fun String.splitCheckNull(): List<String?> {
        val repoVariables = this.split("/")
        val repoVariablesSize = repoVariables.size

        if (repoVariablesSize < 3 || repoVariablesSize > 4) return listOf(null, null, null, null)

        if (repoVariables.getOrNull(3) == null) return repoVariables + listOf(null)

        return repoVariables
    }
}
