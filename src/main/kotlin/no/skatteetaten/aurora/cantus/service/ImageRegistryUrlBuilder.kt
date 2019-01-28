package no.skatteetaten.aurora.cantus.service

import org.springframework.web.util.UriBuilder
import java.net.URI

fun UriBuilder.createTagsUrl(imageRepoDto: ImageRepoDto, registryMetadata: RegistryMetadata): URI {

    val templateVariables = listOf(
        imageRepoDto.namespace,
        imageRepoDto.name
    )

    return this.buildUri(
        registryMetadata.apiSchema,
        "/v2/{namespace}/{name}/tags/list",
        templateVars = templateVariables,
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

    val templateVariables = listOf(
        imageRepoDto.namespace,
        imageRepoDto.name,
        configDigest
    )

    return this.buildUri(
        registryMetadata.apiSchema,
        "/v2/{namespace}/{name}/blobs/sha256:{configdigest}",
        templateVariables,
        registryAddress = imageRepoDto.registry
    )
}

fun UriBuilder.createManifestUrl(imageRepoDto: ImageRepoDto, registryMetadata: RegistryMetadata): URI? {
    if (imageRepoDto.tag == null) {
        return null
    }
    logger.debug("Retrieving manifest from ${imageRepoDto.registry}")
    logger.debug("Retrieving manifest for image ${imageRepoDto.namespace}/${imageRepoDto.name}:${imageRepoDto.tag}")


    val templateVariables = listOf(
        imageRepoDto.namespace,
        imageRepoDto.name,
        imageRepoDto.tag
    )

    return this.buildUri(
        registryMetadata.apiSchema,
        "/v2/{ooooo}/{ppppp}/manifests/{lllll}",
        templateVariables,
        registryAddress = imageRepoDto.registry
    )
}

fun UriBuilder.buildUri(
    apiSchema: String,
    templateUri: String,
    templateVars: List<String>,
    registryAddress: String
) =
    this.scheme(apiSchema)
        .host(registryAddress)
        .path(templateUri)
        .build(templateVars)

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


