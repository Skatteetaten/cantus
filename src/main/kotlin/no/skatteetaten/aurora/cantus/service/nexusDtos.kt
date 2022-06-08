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
    val version: String,
    val assets: List<NexusAsset>
)

data class NexusAsset(
    val lastModified: String?
)
