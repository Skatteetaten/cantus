package no.skatteetaten.aurora.cantus.service

import org.springframework.web.util.UriBuilder
import java.net.URI

fun UriBuilder.createTagsUrl(imageRepoDto: ImageRepoDto, registryMetadata: RegistryMetadata): URI {
    logger.debug("Retrieving tags from ${registryMetadata.fullRegistryUrl}")
    logger.debug("Retrieving tags for image ${imageRepoDto.defaultRepo}")

    return this.buildUri(
        registryMetadata.apiSchema,
        "/v2/{imageGroup}/{imageName}/tags/list",
        imageRepoDto.mappedTemplateVars,
        registryAddress = imageRepoDto.registry,
        port = imageRepoDto.port
    )
}

fun UriBuilder.createConfigUrl(
    imageRepoDto: ImageRepoDto,
    configDigest: String,
    registryMetadata: RegistryMetadata
): URI {
    val configDigestMap = mapOf("configDigest" to configDigest)

    logger.debug("Retrieving manifest V2 config from ${registryMetadata.fullRegistryUrl}")
    logger.debug("Retrieving manifest V2 config for image ${imageRepoDto.manifestRepo}")

    return this.buildUri(
        registryMetadata.apiSchema,
        "/v2/{imageGroup}/{imageName}/blobs/sha256:{configDigest}",
        imageRepoDto.mappedTemplateVars + configDigestMap,
        registryAddress = imageRepoDto.registry,
        port = imageRepoDto.port
    )
}

fun UriBuilder.createManifestUrl(imageRepoDto: ImageRepoDto, registryMetadata: RegistryMetadata): URI? {
    if (imageRepoDto.imageTag == null) {
        return null
    }
    logger.debug("Retrieving manifest from ${registryMetadata.fullRegistryUrl}")
    logger.debug("Retrieving manifest for image ${imageRepoDto.manifestRepo}")

    return this.buildUri(
        registryMetadata.apiSchema,
        "/v2/{imageGroup}/{imageName}/manifests/{imageTag}",
        imageRepoDto.mappedTemplateVars,
        registryAddress = imageRepoDto.registry,
        port = imageRepoDto.port
    )
}

fun UriBuilder.buildUri(
    apiSchema: String,
    templateUri: String,
    templateVars: Map<String, String?>,
    registryAddress: String,
    port: Int?
) =
    this.scheme(apiSchema)
        .host(registryAddress)
        .path(templateUri)
        .port(port ?: -1)
        .build(templateVars)


