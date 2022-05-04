package no.skatteetaten.aurora.cantus.service

data class Version(
    val name: String,
    val lastModified: String
)

data class NexusSearchResponse(
    val items: List<NexusItem>,
    val continuationToken: String?
)

data class NexusItem(
    val id: String,
    val repository: String,
    val name: String,
    val version: String,
    val assets: List<NexusAsset>
)

data class NexusAsset(
    val repository: String,
    val format: String,
    val checksum: NexusCheckSum,
    val lastModified: String
)

data class NexusCheckSum(
    val sha1: String,
    val sha256: String
)

data class ImageDto(
    val repository: String,
    val name: String,
    val version: String,
    val sha256: String
)

data class SingleImageResponse(
    val success: Boolean,
    val image: ImageDto?
)
