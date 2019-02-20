package no.skatteetaten.aurora.cantus.controller

import no.skatteetaten.aurora.cantus.service.DockerRegistryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

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
    ): AuroraResponse<ImageTagResource, CantusFailure> {

        val responses: List<Try<ImageTagResource, CantusFailure>> = tagUrl.map { tagUrl ->
            try {

                val parts = tagUrl.split("/")

            // TODO: Feilhåndtering skal diskuteres under teammøte
            val registryUrl =
                when {
                    parts.size != 4 -> throw BadRequestException(message = "En eller flere av manifestene feilet")
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
                Try.Success(dockerRegistryService
                    .getImageManifestInformation(imageRepoCommand).let { manifestDto ->
                    imageTagResourceAssembler.toResource(
                        manifestDto,
                        requestUrl = tagUrl
                    )
                })
            } catch(e:Throwable) {
                Try.Failure(CantusFailure(tagUrl, e))
            }
        }

        val itemsAndFailure = responses.getSuccessAndFailures()

        return imageTagResourceAssembler.toAuroraResponse(itemsAndFailure.first, itemsAndFailure.second)
    }

    /*@GetMapping("/{affiliation}/{name}/{tag}/manifest")
    fun getManifestInformation(
        @PathVariable affiliation: String,
        @PathVariable name: String,
        @PathVariable tag: String,
        @RequestParam(required = false) dockerRegistryUrl: String?,
        @RequestHeader(required = false, value = "Authorization") bearerToken: String?
    ): AuroraResponse<ImageTagResource> {
        val imageRepoCommand = imageRepoDtoAssembler.createAndValidateCommand(
            overrideRegistryUrl = dockerRegistryUrl,
            name = name,
            namespace = affiliation,
            tag = tag,
            bearerToken = bearerToken
        )
        return dockerRegistryService
            .getImageManifestInformation(imageRepoCommand).let { manifestDto ->
                imageTagResourceAssembler.toResource(
                    manifestDto,
                    "Successfully retrieved manifest information for image ${imageRepoCommand.manifestRepo}"
                )
            }
    }*/

    @GetMapping("/{affiliation}/{name}/tags")
    fun getImageTags(
        @PathVariable affiliation: String,
        @PathVariable name: String,
        @RequestParam(required = false) dockerRegistryUrl: String?,
        @RequestHeader(required = false, value = "Authorization") bearerToken: String?
    ): AuroraResponse<TagResource, CantusFailure> {
        val imageRepoCommand = imageRepoDtoAssembler.createAndValidateCommand(
            overrideRegistryUrl = dockerRegistryUrl,
            name = name,
            namespace = affiliation,
            bearerToken = bearerToken
        )
        val response =
            try {
                Try.Success(dockerRegistryService.getImageTags(imageRepoCommand).let { imageTagsWithTypeDto ->
                    imageTagResourceAssembler.toResource(
                        imageTagsWithTypeDto,
                        "Successfully retrieved tags for image ${imageRepoCommand.defaultRepo}"
                    )
                })
            } catch (e: Throwable) {
                Try.Failure(CantusFailure(imageRepoCommand.manifestRepo, e))
            }
    }

    @GetMapping("/{affiliation}/{name}/tags/semantic")
    fun getImageTagsSemantic(
        @PathVariable affiliation: String,
        @PathVariable name: String,
        @RequestParam(required = false) dockerRegistryUrl: String?,
        @RequestHeader(required = false, value = "Authorization") bearerToken: String?
    ): AuroraResponse<GroupedTagResource, CantusFailure> {
        val imageRepoCommand = imageRepoDtoAssembler.createAndValidateCommand(
            overrideRegistryUrl = dockerRegistryUrl,
            name = name,
            namespace = affiliation,
            bearerToken = bearerToken
        )

        return dockerRegistryService.getImageTags(imageRepoCommand).let { imageTagsWithTypeDto ->
            imageTagResourceAssembler.toGroupedResource(
                imageTagsWithTypeDto,
                "Successfully retrieved tags grouped by semantic version for image ${imageRepoCommand.defaultRepo}"
            )
        }
    }
}
