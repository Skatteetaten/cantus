package no.skatteetaten.aurora.cantus.controller

import no.skatteetaten.aurora.cantus.service.DockerRegistryService
import no.skatteetaten.aurora.cantus.service.ImageManifestDto
import no.skatteetaten.aurora.cantus.service.ImageTagsWithTypeDto
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import uk.q3c.rest.hal.HalResource

@RestController
class DockerRegistryController(
    val dockerRegistryService: DockerRegistryService,
    val imageTagResourceAssembler: ImageTagResourceAssembler,
    val imageRepoCommandAssembler: ImageRepoCommandAssembler
) {

    @GetMapping("/manifest")
    fun getManifestInformationList(
        @RequestParam tagUrl: List<String>,
        @RequestHeader(required = false, value = "Authorization") bearerToken: String?
    ): AuroraResponse<ImageTagResource> {

        val responses = tagUrl.map { getImageTagResponse(it, bearerToken) }

        return imageTagResourceAssembler.imageTagResourceToAuroraResponse(responses)
    }

    @GetMapping("/tags")
    fun getImageTags(
        @RequestParam repoUrl: String,
        @RequestHeader(required = false, value = "Authorization") bearerToken: String?
    ): AuroraResponse<TagResource> {

        val repoUrlParts = repoUrl.split("/")

        if (repoUrlParts.size != 3) return imageTagResourceAssembler.toBadRequestResponse(repoUrl)

        val response =
            getResponse(repoUrl) { dockerService ->
                val imageRepoCommand = getTagRepoUrl(repoUrlParts, bearerToken)

                dockerService.getImageTags(imageRepoCommand).let { tags ->
                    val tagResponse = imageTagResourceAssembler.toTagResource(tags)
                    tagResponse
                }
            }

        return imageTagResourceAssembler.tagResourceToAuroraResponse(response)
    }

    @GetMapping("/tags/semantic")
    fun getImageTagsSemantic(
        @RequestParam repoUrl: String,
        @RequestHeader(required = false, value = "Authorization") bearerToken: String?
    ): AuroraResponse<GroupedTagResource> {
        val repoUrlParts = repoUrl.split("/")

        if (repoUrlParts.size != 3)
            return imageTagResourceAssembler.toBadRequestResponse(repoUrl)

        val response = getResponse(repoUrl) { dockerService ->
            val imageRepoCommand = getTagRepoUrl(repoUrlParts, bearerToken)

            dockerService.getImageTags(imageRepoCommand)
                .let { tags -> imageTagResourceAssembler.toGroupedTagResource(tags, imageRepoCommand.defaultRepo) }
        }

        return imageTagResourceAssembler.groupedTagResourceToAuroraResponse(response)
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
        repoUrlParts: List<String>,
        bearerToken: String?
    ): ImageRepoCommand {

        val dockerRegistryUrl =
            when {
                repoUrlParts[0].isEmpty() -> null
                else -> repoUrlParts[0]
            }
        val namespace = repoUrlParts[1]
        val name = repoUrlParts[2]

        return imageRepoCommandAssembler.createAndValidateCommand(
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
            // TODO: move this to function?
            val parts = urlToTag.split("/")

            val registryUrl =
                when {
                    parts.size != 4 -> return Try.Failure(
                        CantusFailure(
                            urlToTag,
                            BadRequestException(message = "Invalid url=$urlToTag")
                        )
                    )
                    parts[0].isEmpty() -> null
                    else -> parts[0]
                }

            val imageRepoCommand = imageRepoCommandAssembler.createAndValidateCommand(
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

@Component
class ImageTagResourceAssembler(val auroraResponseAssembler: AuroraResponseAssembler) {
    fun imageTagResourceToAuroraResponse(resources: List<Try<ImageTagResource, CantusFailure>>) =
        auroraResponseAssembler.toAuroraResponse(resources)

    fun tagResourceToAuroraResponse(resources: Try<List<TagResource>, CantusFailure>) =
        auroraResponseAssembler.toAuroraResponse(resources)

    fun groupedTagResourceToAuroraResponse(resources: Try<List<GroupedTagResource>, CantusFailure>) =
        auroraResponseAssembler.toAuroraResponse(resources)

    fun toTagResource(imageTagsWithTypeDto: ImageTagsWithTypeDto) =
        imageTagsWithTypeDto.tags.map { TagResource(it.name) }

    fun <T : HalResource> toBadRequestResponse(repoUrl: String) =
        auroraResponseAssembler.toAuroraResponseFailure<T>(
            repoUrl,
            BadRequestException(message = "Invalid url=$repoUrl")
        )

    fun toGroupedTagResource(imageTagsWithTypeDto: ImageTagsWithTypeDto, repoUrl: String) =
        imageTagsWithTypeDto.tags
            .groupBy { it.type }
            .map { groupedTag ->
                GroupedTagResource(
                    group = groupedTag.key.toString(),
                    tagResource = groupedTag.value.map {
                        TagResource(
                            name = it.name,
                            type = it.type
                        )
                    }
                )
            }

    fun toImageTagResource(manifestDto: ImageManifestDto, requestUrl: String) =
        ImageTagResource(
            java = JavaImage.fromDto(manifestDto),
            dockerDigest = manifestDto.dockerDigest,
            dockerVersion = manifestDto.dockerVersion,
            appVersion = manifestDto.appVersion,
            auroraVersion = manifestDto.auroraVersion,
            timeline = ImageBuildTimeline.fromDto(manifestDto),
            node = NodeJsImage.fromDto(manifestDto),
            requsestUrl = requestUrl
        )
}