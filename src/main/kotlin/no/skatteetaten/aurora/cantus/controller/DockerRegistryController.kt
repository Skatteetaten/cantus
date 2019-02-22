package no.skatteetaten.aurora.cantus.controller

import no.skatteetaten.aurora.cantus.service.DockerRegistryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import uk.q3c.rest.hal.HalResource

@RestController
class DockerRegistryController(
    val dockerRegistryService: DockerRegistryService,
    val imageTagResourceAssembler: ImageTagResourceAssembler,
    val imageRepoDtoAssembler: ImageRepoDtoAssembler
) {

    @GetMapping("/manifest")
    fun getManifestInformationList(
        @RequestParam tagUrl: List<String>,
        @RequestHeader(required = false, value = "Authorization") bearerToken: String?
    ): AuroraResponse<HalResource, CantusFailure> {

        val responses = tagUrl.map { getImageTagResponse(it, bearerToken) }

        return imageTagResourceAssembler.toAuroraResponse(responses)
    }

    @GetMapping("/tags")
    fun getImageTags(
        @RequestParam repoUrl: String,
        @RequestHeader(required = false, value = "Authorization") bearerToken: String?
    ): AuroraResponse<TagResource, CantusFailure> {

        val imageRepoCommand = getTagRepoUrl(repoUrl, bearerToken)

        val response =
            getResponse(repoUrl) { dockerService ->
                dockerService.getImageTags(imageRepoCommand).let { tags ->
                    imageTagResourceAssembler.toTagResource(tags)
                }
            }

        return imageTagResourceAssembler.toAuroraResponse(response)
    }

    @GetMapping("/tags/semantic")
    fun getImageTagsSemantic(
        @RequestParam repoUrl: String,
        @RequestHeader(required = false, value = "Authorization") bearerToken: String?
    ): AuroraResponse<GroupedTagResource, CantusFailure> {

        val imageRepoCommand = getTagRepoUrl(repoUrl, bearerToken)

        val response = getResponse(repoUrl) { dockerService ->
            dockerService.getImageTags(imageRepoCommand)
                .let { tags -> imageTagResourceAssembler.toGroupedTagResource(tags, imageRepoCommand.defaultRepo) }
        }

        return imageTagResourceAssembler.toAuroraResponse(response)
    }

    private final inline fun <reified T : HalResource> getResponse(
        repoUrl: String,
        fn: (DockerRegistryService) -> List<T>
    ): Try<List<T>, CantusFailure> =
        try {
            Try.Success(fn(dockerRegistryService))
        } catch (e: Throwable) {
            Try.Failure(CantusFailure(repoUrl, e))
        }

    private fun getTagRepoUrl(
        repoUrl: String,
        bearerToken: String?
    ): ImageRepoCommand {
        val parts = repoUrl.split("/")

        if (parts.size != 3) Try.Failure(CantusFailure(repoUrl, BadRequestException(message = "Invalid repo url")))

        val dockerRegistryUrl =
            when {
                parts[0].isEmpty() -> null
                else -> parts[0]
            }
        val namespace = parts[1]
        val name = parts[2]

        return imageRepoDtoAssembler.createAndValidateCommand(
            overrideRegistryUrl = dockerRegistryUrl,
            name = name,
            namespace = namespace,
            bearerToken = bearerToken
        )
    }

    private fun getImageTagResponse(
        urlToTag: String,
        bearerToken: String?
    ): Try<ImageTagResource, CantusFailure> {
        try {

            val parts = urlToTag.split("/")
                
            val registryUrl =
                when {
                    parts.size != 4 -> return Try.Failure(
                        CantusFailure(
                            urlToTag,
                            BadRequestException(message = "Ugyldig urlToTag")
                        )
                    )
                    parts[0].isEmpty() -> null
                    else -> parts[0]
                }

            val imageRepoCommand = imageRepoDtoAssembler.createAndValidateCommand(
                overrideRegistryUrl = registryUrl,
                namespace = parts[1],
                name = parts[2],
                tag = parts[3],
                bearerToken = bearerToken
            )

            return Try.Success(dockerRegistryService
                .getImageManifestInformation(imageRepoCommand)
                .let { imageTagResourceAssembler.toImageTagResource(manifestDto = it, requestUrl = urlToTag) }
            )
        } catch (e: Throwable) {
            return Try.Failure(CantusFailure(urlToTag, e))
        }
    }
}

