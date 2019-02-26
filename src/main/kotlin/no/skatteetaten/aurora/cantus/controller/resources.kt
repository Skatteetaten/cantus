package no.skatteetaten.aurora.cantus.controller

import com.fasterxml.jackson.annotation.JsonIgnore
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

data class CantusFailure(
    val url: String,
    @JsonIgnore val error: Throwable
) {
    val errorMessage: String = error.message ?: "Unknown error (${error::class.simpleName})"
}

sealed class Try<out A, out B> {
    class Success<A>(val value: A) : Try<A, Nothing>()
    class Failure<B>(val value: B) : Try<Nothing, B>()
}

inline fun <reified S : Any, reified T : Any> List<Try<S, T>>.getSuccessAndFailures(): Pair<List<S>, List<T>> {
    val items = this.mapNotNull {
        if (it is Try.Success) {
            it.value
        } else null
    }

    val failure = this.mapNotNull {
        if (it is Try.Failure) {
            it.value
        } else null
    }

    return Pair(items, failure)
}

inline fun <reified S : Any, reified T : Any> Try<S, T>.getSuccessAndFailures(): Pair<List<S>, List<T>> {
    val item = if (this is Try.Success) {
        listOf(this.value)
    } else emptyList()
    val failure = if (this is Try.Failure) {
        listOf(this.value)
    } else emptyList()

    return Pair(item, failure)
}

data class AuroraResponse<T : HalResource?, F : CantusFailure?>(
    val items: List<T> = emptyList(),
    val failure: List<F> = emptyList(),
    val success: Boolean = true,
    val message: String = "OK",
    val failureCount: Int = failure.size,
    val successCount: Int = items.size,
    val count: Int = failureCount + successCount
) : HalResource()

@Component
class ImageTagResourceAssembler {
    final inline fun <reified T : HalResource> toAuroraResponse(responses: List<Try<T, CantusFailure>>): AuroraResponse<T, CantusFailure> {
        val (items, failures) = responses.getSuccessAndFailures()

        return AuroraResponse(
            success = failures.isEmpty(),
            message = if (failures.isNotEmpty()) failures.first().errorMessage else "Success",
            items = items,
            failure = failures
        )
    }

    final inline fun <reified T : HalResource> toAuroraResponse(responses: Try<List<T>, CantusFailure>): AuroraResponse<T, CantusFailure> {
        val itemsAndFailure = responses.getSuccessAndFailures()
        val items = itemsAndFailure.first.firstOrNull() ?: emptyList()
        val failures = itemsAndFailure.second

        return AuroraResponse(
            success = failures.isEmpty(),
            message = if (failures.isNotEmpty()) failures.first().error.message ?: "" else "Success",
            items = items,
            failure = failures
        )
    }

    //TODO: exception.message ?: ""
    final inline fun <reified T : HalResource> toAuroraResponseFailure(url: String, exception: Throwable) =
        AuroraResponse<T, CantusFailure>(
            success = false,
            message = exception.message ?: "",
            failure = listOf(CantusFailure(url, exception))
        )

    fun toTagResource(tags: ImageTagsWithTypeDto) =
        tags.tags.map {
            TagResource(it.name)
        }

    fun toGroupedTagResource(tags: ImageTagsWithTypeDto, repoUrl: String) =
        tags.tags
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
