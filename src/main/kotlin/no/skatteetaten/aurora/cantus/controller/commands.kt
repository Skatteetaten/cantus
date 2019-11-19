package no.skatteetaten.aurora.cantus.controller

import mu.KotlinLogging
import no.skatteetaten.aurora.cantus.AuroraIntegration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient

private val logger = KotlinLogging.logger {}
data class TagCommand(
    val from: String,
    val to: String
    // TODO: We could here add an additionalTags string with csv of aditional tags for ease of use.
    // TODO: The only thing you need to do then is push the manifest several times
)

data class ImageRepoCommand(
    val url: String,
    val registry: String,
    val imageGroup: String,
    val imageName: String,
    val imageTag: String? = null,
    val token: String? = null,
    val authType: AuroraIntegration.AuthType
) {
    val manifestRepo: String
        get() = listOf(imageGroup, imageName, imageTag).joinToString("/")
    val defaultRepo: String
        get() = listOf(imageGroup, imageName).joinToString("/")
    val artifactRepo: String
        get() = listOf(registry, imageGroup, imageName).joinToString("/")
    val fullRepoCommand: String
        get() = listOf(registry, imageGroup, imageName, imageTag).joinToString("/")
    val mappedTemplateVars: Map<String, String?>
        get() = mapOf(
            "imageGroup" to imageGroup,
            "imageName" to imageName,
            "imageTag" to imageTag
        )

    fun createRequest(
        webClient: WebClient,
        path: String,
        method: HttpMethod = HttpMethod.GET,
        pathVariables: Map<String, String> = emptyMap()
    ) = webClient
        .method(method)
        .uri(
            "${this.url}/$path",
            this.mappedTemplateVars + pathVariables
        )
        .headers { headers ->
            this.token?.let {
                headers.set(HttpHeaders.AUTHORIZATION, "${this.authType} $it")
            }
        }
}

data class ImageRepo(
    val registry: String,
    val imageGroup: String,
    val imageName: String,
    val imageTag: String?
)

fun AuroraIntegration.findRegistry(registry: String): AuroraIntegration.DockerRegistry? =
    this.docker.values.find { it.url == registry && it.enabled}

private const val SIZE_OF_COMPLETE_IMAGE_REPO = 4
private const val SIZE_OF_IMAGE_REPO_WITHOUT_TAG = 3
private const val INDEX_OF_IMAGE_TAG = 3

@Component
class ImageRepoCommandAssembler(
    val aurora: AuroraIntegration
) {
    fun createAndValidateCommand(
        url: String,
        bearerToken: String? = null
    ): ImageRepoCommand {
        val imageRepo = url.toImageRepo()

        logger.info { aurora.docker.values }
        val registry = aurora.findRegistry(imageRepo.registry)

        require(registry != null) { "Invalid Docker Registry URL url=${imageRepo.registry}" }
        require(registry.auth != null) { "Registry authType is required" }
        require(registry.auth != AuroraIntegration.AuthType.None && bearerToken.isNotNullOrBlank()) {
            "Registry required authentication"
        }

        val scheme = if (registry.https) "https://" else "http://"

        return ImageRepoCommand(
            registry = imageRepo.registry,
            imageName = imageRepo.imageName,
            imageGroup = imageRepo.imageGroup,
            imageTag = imageRepo.imageTag,
            authType = registry.auth,
            token = bearerToken?.split(" ")?.last(),
            url = "$scheme${imageRepo.registry}/v2"
        )
    }

    private fun String?.isNotNullOrBlank() = !this.isNullOrBlank()

    private fun String.toImageRepo(): ImageRepo {
        val repoVariables = this.split("/")
        val repoVariablesSize = repoVariables.size
        this.verifyUrlPattern(repoVariablesSize)
        return repoVariables.toImageRepo()
    }

    private fun List<String>.toImageRepo(): ImageRepo{
        if(repoUrlHasColonBetweenNameAndTag(this.size, this)) {
          val (name, tag) = this[2].split(":")
            return ImageRepo(
                registry = this[0],
                imageGroup = this[1],
                imageName = name,
                imageTag = tag
            )
        }
        return ImageRepo(
            registry = this[0],
            imageGroup = this[1],
            imageName = this[2],
            imageTag = this.getOrNull(INDEX_OF_IMAGE_TAG)
        )
    }
    private fun repoUrlHasColonBetweenNameAndTag(
        repoVariablesSize: Int,
        repoVariables: List<String>
    ) = repoVariablesSize == SIZE_OF_IMAGE_REPO_WITHOUT_TAG && repoVariables[2].contains(":")

    private fun String.verifyUrlPattern(repoVariablesSize: Int) {
        require(
            repoVariablesSize == SIZE_OF_IMAGE_REPO_WITHOUT_TAG || repoVariablesSize ==
                SIZE_OF_COMPLETE_IMAGE_REPO
        ) {
            "repo url=$this malformed pattern=url:port/group/name:tag"
        }
    }
}
