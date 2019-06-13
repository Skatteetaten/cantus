package no.skatteetaten.aurora.cantus.controller

import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import mu.KotlinLogging
import no.skatteetaten.aurora.cantus.service.DockerRegistryService
import no.skatteetaten.aurora.cantus.service.ImageManifestDto
import no.skatteetaten.aurora.cantus.service.ImageTagsWithTypeDto
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import uk.q3c.rest.hal.HalResource

private val logger = KotlinLogging.logger {}

// TODO: Validate if bearer is required and not set EARLY, not in DockerRegistryService

@RestController
class DockerRegistryController(
    val dockerRegistryService: DockerRegistryService,
    val imageTagResourceAssembler: ImageTagResourceAssembler,
    val imageRepoCommandAssembler: ImageRepoCommandAssembler,
    val threadPoolContext: ExecutorCoroutineDispatcher
) {

    data class TagCommandResource(val result: Boolean) : HalResource()

    data class TagCommand(
        val from: String,
        val fromAuth: String? = null,
        val to: String,
        val toAuth: String? = null
    )

    @PostMapping("/tag")
    fun tagDockerImage(
        @RequestBody tagCommand: TagCommand
    ): AuroraResponse<TagCommandResource> {

        logger.debug("command=$tagCommand")
        // TODO: Error handling, if wrongly formatted url silently fails
        val from = imageRepoCommandAssembler.createAndValidateCommand(tagCommand.from, tagCommand.fromAuth)!!
        val to = imageRepoCommandAssembler.createAndValidateCommand(tagCommand.to, tagCommand.toAuth)!!

        logger.debug("command objects from=$from to=$to")
        val result = dockerRegistryService.tagImage(from, to)

        return AuroraResponse(
            success = true,
            items = listOf(TagCommandResource(result))
        )
    }

    @PostMapping("/manifest")
    fun getManifestInformationList(
        @RequestBody tagUrls: List<String>,
        @RequestHeader(required = false, value = HttpHeaders.AUTHORIZATION) bearerToken: String?
    ): AuroraResponse<ImageTagResource> {

        val responses =
            runBlocking(MDCContext() + threadPoolContext) {
                val deferred =
                    tagUrls.map {
                        async { getImageTagResource(bearerToken, it) }
                    }
                deferred.map { it.await() }
            }

        return imageTagResourceAssembler.imageTagResourceToAuroraResponse(responses)
    }

    private fun getImageTagResource(
        bearerToken: String?,
        tagUrl: String
    ): Try<ImageTagResource, CantusFailure> {
        return getResponse(bearerToken, tagUrl) { dockerService, imageRepoCommand ->
            dockerService.getImageManifestInformation(imageRepoCommand)
                .let { imageManifestDto ->
                    imageTagResourceAssembler.toImageTagResource(
                        manifestDto = imageManifestDto,
                        requestUrl = tagUrl
                    )
                }
        }
    }

    @GetMapping("/tags")
    fun getImageTags(
        @RequestParam repoUrl: String,
        @RequestHeader(required = false, value = HttpHeaders.AUTHORIZATION) bearerToken: String?
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
        @RequestHeader(required = false, value = HttpHeaders.AUTHORIZATION) bearerToken: String?
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
                ?: return Try.Failure(CantusFailure(repoUrl, BadRequestException("Invalid url=$repoUrl")))

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
            requestUrl = requestUrl
        )
}
