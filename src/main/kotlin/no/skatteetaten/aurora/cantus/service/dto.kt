package no.skatteetaten.aurora.cantus.service

import com.fasterxml.jackson.databind.JsonNode


data class ImageTagsResponseDto(val tags: List<String>)

data class ImageManifestResponseDto(
    val contentType: String? = null,
    val dockerContentDigest: String? = null,
    val manifestBody: JsonNode? = null,
    val statusCode: Int
)

data class ImageManifestDto(
    val auroraVersion: String? = null,
    val appVersion: String? = null,
    val buildStarted: String? = null,
    val buildEnded: String? = null,
    val dockerVersion: String,
    val dockerDigest: String,
    val javaVersionMajor: String? = null,
    val javaVersionMinor: String? = null,
    val javaVersionBuild: String? = null,
    val jolokiaVersion: String? = null,
    val nodeVersion: String? = null
)


data class ImageTagTypedDto(val name: String, val type: ImageTagType = ImageTagType.typeOf(name))
data class ImageTagsWithTypeDto(val tags: List<ImageTagTypedDto>)
