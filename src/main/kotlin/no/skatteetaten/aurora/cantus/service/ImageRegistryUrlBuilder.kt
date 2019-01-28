package no.skatteetaten.aurora.cantus.service

import org.springframework.web.util.UriBuilder
import java.net.URI

fun UriBuilder.createTagsUrl(imageRepoDto: ImageRepoDto, registryMetadata: RegistryMetadata): URI {

    val templateVariables = mapOf(
        "imageGroup" to imageRepoDto.imageGroup,
        "imageName" to imageRepoDto.imageName
    )

    return this.buildUri(
        registryMetadata.apiSchema,
        "/v2/{imageGroup}/{imageName}/tags/list",
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
        imageRepoDto.imageGroup,
        imageRepoDto.imageName,
        configDigest
    )

    return this.buildUri(
        registryMetadata.apiSchema,
        "/v2/{imageGroup}/{imageName}/blobs/sha256:{configdigest}",
        templateVariables,
        registryAddress = imageRepoDto.registry
    )
}

fun UriBuilder.createManifestUrl(imageRepoDto: ImageRepoDto, registryMetadata: RegistryMetadata): URI? {
    if (imageRepoDto.imageTag == null) {
        return null
    }
    logger.debug("Retrieving manifest from ${imageRepoDto.registry}")
    logger.debug("Retrieving manifest for image ${imageRepoDto.imageGroup}/${imageRepoDto.imageName}:${imageRepoDto.imageTag}")


    val templateVariables = listOf(
        imageRepoDto.imageGroup,
        imageRepoDto.imageName,
        imageRepoDto.imageTag
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


