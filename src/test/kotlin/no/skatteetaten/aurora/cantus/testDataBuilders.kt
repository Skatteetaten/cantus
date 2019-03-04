package no.skatteetaten.aurora.cantus

import no.skatteetaten.aurora.cantus.service.ImageManifestDto
import no.skatteetaten.aurora.cantus.service.JavaImageDto

class ImageManifestDtoBuilder {
    fun build() = ImageManifestDto(
        auroraVersion = "2",
        dockerVersion = "2",
        dockerDigest = "sah",
        appVersion = "2",
        nodeVersion = "2",
        java = JavaImageDto(
            major = "2",
            minor = "0",
            build = "0"
        )
    )
}