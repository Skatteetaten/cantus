package no.skatteetaten.aurora.cantus.controller

import no.skatteetaten.aurora.cantus.service.ImageTagType
import uk.q3c.rest.hal.HalResource
import java.time.Instant

data class TagResource(val name: String, val type: ImageTagType = ImageTagType.typeOf(name)) : HalResource()

data class GroupedTagResource(val group:String) : HalResource()


data class ImageTagResource(
    val auroraVersion: String? = null,
    val appVersion:String? = null,
    val timeline: Map<String, Instant> = emptyMap(),
    val dockerVersion: String,
    val dockerDigest: String,
    val java:JavaImage? = null,
    val node:NodeImage? = null
) : HalResource()

data class JavaImage(
    val major: String,
    val minor:String,
    val build:String,
    val jolokia: String?
)


data class NodeImage(
    val nodeVersion: String
)

data class AuroraResponse<T : HalResource>(
    val items: List<T> = emptyList(),
    val success: Boolean = true,
    val message: String = "OK",
    val exception: Throwable? = null,
    val count: Int = items.size
) : HalResource()

/*val java= if (manifestInformationMap.containsKey("JAVA_VERSION_MAJOR")) {
            JavaImage(
                major = manifestInformationMap["JAVA_VERSION_MAJOR"]!!,
                minor = manifestInformationMap["JAVA_VERSION_MINOR"]!!,
                build = manifestInformationMap["JAVA_VERSION_BUILD"]!!,
                jolokia = manifestInformationMap["JOLOKIA_VERSION"]!!
            )
        } else null

        val node= if (manifestInformationMap.containsKey("NODE_VERSION")) {
            NodeImage(
                nodeVersion = manifestInformationMap["NODE_VERSION"]!!
            )
        } else null
        return ImageTagResource(
            auroraVersion = manifestInformationMap["AURORA_VERSION"],
            appVersion = manifestInformationMap["APP_VERSION"],
            timeline = mapOf(
                "BUILD_STARTED" to Instant.parse(manifestInformationMap["IMAGE_BUILD_TIME"] ?: Instant.EPOCH.toString()),
                "BUILD_DONE" to Instant.parse(manifestInformationMap["CREATED"] ?: Instant.EPOCH.toString())
            ),
            dockerDigest = manifestInformationMap["DOCKER_CONTENT_DIGEST"]!!,
            dockerVersion = manifestInformationMap["DOCKER_VERSION"]!!,
            java = java,
            node = node
        )*/