package no.skatteetaten.aurora.cantus.controller

import no.skatteetaten.aurora.cantus.service.ImageManifestDto
import no.skatteetaten.aurora.cantus.service.ImageTagType
import no.skatteetaten.aurora.cantus.service.ImageTagsWithTypeDto
import org.springframework.stereotype.Component
import uk.q3c.rest.hal.HalResource
import java.time.Instant

data class TagResource(val name: String, val type: ImageTagType = ImageTagType.typeOf(name)) : HalResource()

data class GroupedTagResource(
    val group: String,
    val tagResource: List<TagResource>,
    val itemsInGroup: Int = tagResource.size
) : HalResource()

data class ImageTagResource(
    val auroraVersion: String? = null,
    val appVersion: String? = null,
    val timeline: ImageBuildTimeline,
    val dockerVersion: String,
    val dockerDigest: String,
    val java: JavaImage? = null,
    val node: NodeJsImage? = null,
    val requsestUrl: String
) : HalResource()

data class JavaImage(
    val major: String,
    val minor: String,
    val build: String,
    val jolokia: String?
) {
    companion object {
        fun fromDto(dto: ImageManifestDto): JavaImage? {
            if (dto.java == null) {
                return null
            }

            return JavaImage(
                major = dto.java.major,
                minor = dto.java.minor,
                build = dto.java.build,
                jolokia = dto.jolokiaVersion
            )
        }
    }
}

data class ImageBuildTimeline(
    val buildStarted: Instant?,
    val buildEnded: Instant?
) {
    companion object {
        fun fromDto(dto: ImageManifestDto): ImageBuildTimeline {
            return ImageBuildTimeline(
                try {
                    Instant.parse(dto.buildStarted)
                } catch (e: Exception) {
                    null
                },
                try {
                    Instant.parse(dto.buildEnded)
                } catch (e: Exception) {
                    null
                }
            )
        }
    }
}

data class NodeJsImage(
    val nodeJsVersion: String
) {
    companion object {
        fun fromDto(dto: ImageManifestDto): NodeJsImage? {
            if (dto.nodeVersion == null) {
                return null
            }

            return NodeJsImage(dto.nodeVersion)
        }
    }
}

data class AuroraResponse<T : HalResource>(
    val items: List<T> = emptyList(),
    val success: Boolean = true,
    val message: String = "OK",
    val exception: Throwable? = null,
    val count: Int = items.size
) : HalResource() {
    // TODO: trenger vi denne?
    val item: T?
        get() {
            if (count == 1) {
                return items.first()
            }
            return null
        }
}

@Component
class ImageTagResourceAssembler {
    fun toAuroraResponse(resources: List<ImageTagResource>, message: String) =
        AuroraResponse(
            success = true,
            message = message,
            items = resources
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

    fun toResource(manifestDto: ImageManifestDto, requestUrl: String) =
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