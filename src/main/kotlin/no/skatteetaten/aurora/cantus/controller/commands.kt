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

data class ImageRepo(
    val registry: String? = null,
    val imageGroup: String? = null,
    val imageName: String? = null,
    val imageTag: String? = null
)

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

    private fun String.splitCheckNull(): ImageRepo {
        val repoVariables = this.split("/")
        val repoVariablesSize = repoVariables.size

        if (repoVariablesSize < 3 || repoVariablesSize > 4) return ImageRepo()

        return ImageRepo(
            registry = repoVariables.getOrNull(0),
            imageGroup = repoVariables.getOrNull(1),
            imageName = repoVariables.getOrNull(2),
            imageTag = repoVariables.getOrNull(3)
        )
    }
}
