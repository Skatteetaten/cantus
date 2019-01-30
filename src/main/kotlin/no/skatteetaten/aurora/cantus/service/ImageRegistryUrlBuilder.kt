package no.skatteetaten.aurora.cantus.service

import no.skatteetaten.aurora.cantus.controller.ImageRepoCommand
import org.springframework.web.util.UriBuilder
import java.net.URI

fun UriBuilder.createTagsUrl(imageRepoCommand: ImageRepoCommand, registryMetadata: RegistryMetadata): URI {

    logger.debug("Retrieving type=tags from  url=${registryMetadata.fullRegistryUrl} image=${imageRepoCommand.defaultRepo}")
    return this.buildUri(
        registryMetadata.apiSchema,
        "/v2/{imageGroup}/{imageName}/tags/list",
        imageRepoCommand.mappedTemplateVars,
        registryAddress = imageRepoCommand.registry,
        port = imageRepoCommand.port
    )
}

fun UriBuilder.createConfigUrl(
    imageRepoCommand: ImageRepoCommand,
    configDigest: String,
    registryMetadata: RegistryMetadata
): URI {
    val configDigestMap = mapOf("configDigest" to configDigest)
    logger.debug("Retrieving type=config from schemaVersion=v2 url=${registryMetadata.fullRegistryUrl} image=${imageRepoCommand.manifestRepo}")

    return this.buildUri(
        apiSchema = registryMetadata.apiSchema,
        templateUri = "/v2/{imageGroup}/{imageName}/blobs/sha256:{configDigest}",
        templateVars = imageRepoCommand.mappedTemplateVars + configDigestMap,
        registryAddress = imageRepoCommand.registry,
        port = imageRepoCommand.port
    )
}

fun UriBuilder.createManifestUrl(imageRepoCommand: ImageRepoCommand, registryMetadata: RegistryMetadata): URI? {
    if (imageRepoCommand.imageTag == null) {
        return null
    }
    logger.debug("Retrieving type=manifest from schemaVersion=v1 url=${registryMetadata.fullRegistryUrl} image=${imageRepoCommand.manifestRepo}")

    return this.buildUri(
        apiSchema = registryMetadata.apiSchema,
        templateUri = "/v2/{imageGroup}/{imageName}/manifests/{imageTag}",
        templateVars = imageRepoCommand.mappedTemplateVars,
        registryAddress = imageRepoCommand.registry,
        port = imageRepoCommand.port
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
        .port(port ?: 80)
        .build(templateVars)
