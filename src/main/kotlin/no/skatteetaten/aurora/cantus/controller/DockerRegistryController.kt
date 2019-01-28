package no.skatteetaten.aurora.cantus.controller

import no.skatteetaten.aurora.cantus.service.DockerRegistryService
import no.skatteetaten.aurora.cantus.service.ImageManifestDto
import no.skatteetaten.aurora.cantus.service.ImageRepoDto
import no.skatteetaten.aurora.cantus.service.ImageTagsWithTypeDto
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
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

    @GetMapping("/{affiliation}/{name}/{tag}/manifest")
    fun getManifestInformation(
        @PathVariable affiliation: String,
        @PathVariable name: String,
        @PathVariable tag: String,
        @RequestParam(required = false) dockerRegistryUrl: String?,
        @RequestHeader(required = false, value = "Authorization") bearerToken: String?
    ): AuroraResponse<ImageTagResource> {
        val imageRepoDto = imageRepoDtoAssembler.toDto(
            overrideRegistryUrl = dockerRegistryUrl,
            name = name,
            namespace = affiliation,
            tag = tag,
            bearerToken = bearerToken
        )
        return dockerRegistryService
            .getImageManifestInformation(imageRepoDto).let { manifestDto ->
                imageTagResourceAssembler.toResource(
                    manifestDto,
                    "Successfully retrieved manifest information for image ${imageRepoDto.manifestRepo}"
                )
            }
    }

    @GetMapping("/{affiliation}/{name}/tags")
    fun getImageTags(
        @PathVariable affiliation: String,
        @PathVariable name: String,
        @RequestParam(required = false) dockerRegistryUrl: String?,
        @RequestHeader(required = false, value = "Authorization") bearerToken: String?
    ) : AuroraResponse<TagResource>{
        val imageRepoDto = imageRepoDtoAssembler.toDto(
            overrideRegistryUrl = dockerRegistryUrl,
            name = name,
            namespace = affiliation,
            bearerToken = bearerToken
        )
        return dockerRegistryService.getImageTags(imageRepoDto).let { imageTagsWithTypeDto ->
            imageTagResourceAssembler.toResource(
                imageTagsWithTypeDto,
                "Successfully retrieved tags for image ${imageRepoDto.defaultRepo}"
            )
        }
    }

    @GetMapping("/{affiliation}/{name}/tags/semantic")
    fun getImageTagsSemantic(
        @PathVariable affiliation: String,
        @PathVariable name: String,
        @RequestParam(required = false) dockerRegistryUrl: String?,
        @RequestHeader(required = false, value = "Authorization") bearerToken: String?
    ) {
        val imageRepoDto = imageRepoDtoAssembler.toDto(
            overrideRegistryUrl = dockerRegistryUrl,
            name = name,
            namespace = affiliation,
            bearerToken = bearerToken
        )

        return dockerRegistryService.getImageTags(imageRepoDto).let { imageTagsWithTypeDto ->
            imageTagResourceAssembler.toGroupedResource(
                imageTagsWithTypeDto,
                "Successfully retrieved tags grouped by semantic version for image ${imageRepoDto.defaultRepo}"
            )
        }
    }
}

@Component
class ImageRepoDtoAssembler(
    @Value("\${cantus.docker.url}") val registryUrl: String,
    @Value("\${cantus.docker.urlsallowed}") val allowedRegistryUrls: List<String>
) {
    fun toDto(
        overrideRegistryUrl: String?,
        name: String,
        namespace: String,
        tag: String? = null,
        bearerToken: String? = null
    ): ImageRepoDto {
        val validatedRegistryUrl = if (overrideRegistryUrl != null) {
            validateDockerRegistryUrl(
                urlToValidate = overrideRegistryUrl,
                allowedUrls = allowedRegistryUrls
            )
        } else registryUrl

        return ImageRepoDto(registry = validatedRegistryUrl, imageGroup = namespace, imageName = name, imageTag = tag, bearerToken = bearerToken)
    }

    private fun validateDockerRegistryUrl(urlToValidate: String, allowedUrls: List<String>): String {
        if (!allowedUrls.any { allowedUrl -> urlToValidate == allowedUrl }) {
            throw BadRequestException("Invalid Docker Registry URL")
        } else {
            return urlToValidate
        }
    }
}

@Component
class ImageTagResourceAssembler {
    fun toResource(manifestDto: ImageManifestDto, message: String): AuroraResponse<ImageTagResource> =
        AuroraResponse(
            success = true,
            message = message,
            items = listOf(
                ImageTagResource(
                    java = JavaImage.fromDto(manifestDto),
                    dockerDigest = manifestDto.dockerDigest,
                    dockerVersion = manifestDto.dockerVersion,
                    appVersion = manifestDto.appVersion,
                    auroraVersion = manifestDto.auroraVersion,
                    timeline = ImageBuildTimeline.fromDto(manifestDto),
                    node = NodeImage.fromDto(manifestDto)
                )
            )
        )

    fun toResource(tags: ImageTagsWithTypeDto, message: String): AuroraResponse<TagResource> =
        AuroraResponse(
            success = true,
            message = message,
            items = tags.tags.map {
                TagResource(
                    name = it.name
                )
            }
        )

    fun toGroupedResource(tags: ImageTagsWithTypeDto, message: String): AuroraResponse<GroupedTagResource> =
        AuroraResponse(
            success = true,
            message = message,
            items = tags.tags.groupBy {
                it.type
            }.map { groupedTag ->
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
        )
}