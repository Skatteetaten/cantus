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

        val responses = tagUrl.map {
            getResponse(bearerToken, it) { dockerService, imageRepoCommand ->
                dockerService
                    .getImageManifestInformation(imageRepoCommand)
                    .let { imageManifestDto ->
                        imageTagResourceAssembler.toImageTagResource(manifestDto = imageManifestDto, requestUrl = it) }
            }
        }

        return imageTagResourceAssembler.imageTagResourceToAuroraResponse(responses)
    }

    @GetMapping("/tags")
    fun getImageTags(
        @RequestParam repoUrl: String,
        @RequestHeader(required = false, value = "Authorization") bearerToken: String?
    ): AuroraResponse<TagResource> {

        val response =
            getResponse(bearerToken, repoUrl) { dockerService, imageRepoCommand ->
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

        val response = getResponse(bearerToken, repoUrl) { dockerService, imageRepoCommand ->
            dockerService.getImageTags(imageRepoCommand)
                .let { tags -> imageTagResourceAssembler.toGroupedTagResource(tags, imageRepoCommand.defaultRepo) }
        }

        return imageTagResourceAssembler.groupedTagResourceToAuroraResponse(response)
    }

    private final inline fun <reified T : Any> getResponse(
        bearerToken: String?,
        repoUrl: String,
        fn: (DockerRegistryService, ImageRepoCommand) -> T
    ): Try<T, CantusFailure> {
        try {
            val imageRepoCommand = imageRepoCommandAssembler.createAndValidateCommand(repoUrl, bearerToken)
                ?: return Try.Failure(CantusFailure(repoUrl, BadRequestException("Invalid repoUrl or tagUrl")))

            return Try.Success(fn(dockerRegistryService, imageRepoCommand))
        } catch (e: Throwable) {
            return Try.Failure(CantusFailure(repoUrl, e))
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