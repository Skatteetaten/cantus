package no.skatteetaten.aurora.cantus.service

import org.springframework.web.util.UriBuilder
import java.net.URI

fun UriBuilder.createTagsUrl(imageRepoDto: ImageRepoDto, registryMetadata: RegistryMetadata): URI {
    val imageMap = mapOf(
        "namespace" to imageRepoDto.namespace,
        "name" to imageRepoDto.name
    )

    return this.buildUri(
        registryMetadata.apiSchema,
        "/v2/{namespace}/{name}/tags/list",
        templateVars = imageMap,
        registryAddress = imageRepoDto.registry
    )
}

fun UriBuilder.createConfigUrl(
    imageRepoDto: ImageRepoDto,
    configDigest: String?,
    registryMetadata: RegistryMetadata
): URI? {
    if (configDigest == null) {
        return null
    }

    val imageMap = mapOf(
        "namespace" to imageRepoDto.namespace,
        "name" to imageRepoDto.name,
        "configdigest" to configDigest
    )

    return this.buildUri(
        registryMetadata.apiSchema,
        "/v2/{namespace}/{name}/blobs/sha256:{configdigest}",
        imageMap,
        registryAddress = imageRepoDto.registry
    )
}

fun UriBuilder.createManifestUrl(imageRepoDto: ImageRepoDto, registryMetadata: RegistryMetadata): URI? {
    if (imageRepoDto.tag == null) {
        return null
    }
    logger.debug("Retrieving manifest from ${imageRepoDto.registry}")
    logger.debug("Retrieving manifest for image ${imageRepoDto.namespace}/${imageRepoDto.name}:${imageRepoDto.tag}")

    val imageMap = mapOf(
        "namespace" to imageRepoDto.namespace,
        "name" to imageRepoDto.name,
        "tag" to imageRepoDto.tag
    )

    return this.buildUri(
        registryMetadata.apiSchema,
        "/v2/{namespace}/{name}/manifests/{tag}",
        imageMap,
        registryAddress = imageRepoDto.registry
    )
}

fun UriBuilder.buildUri(
    apiSchema: String,
    templateUri: String,
    templateVars: Map<String, String>,
    registryAddress: String
) =
    this.scheme(apiSchema)
        .host(registryAddress)
        .path(templateUri)
        .build(templateVars)

