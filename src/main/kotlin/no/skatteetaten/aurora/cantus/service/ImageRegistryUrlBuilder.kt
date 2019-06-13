package no.skatteetaten.aurora.cantus.service

import mu.KotlinLogging
import no.skatteetaten.aurora.cantus.controller.ImageRepoCommand
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class ImageRegistryUrlBuilder {
    fun createTagsUrl(
        imageRepoCommand: ImageRepoCommand,
        registryMetadata: RegistryMetadata
    ): String {
        logger.debug("Retrieving type=tags from  url=${registryMetadata.fullRegistryUrl} image=${imageRepoCommand.defaultRepo}")
        return "${registryMetadata.fullRegistryUrl}/{imageGroup}/{imageName}/tags/list"
    }

    fun createBlobUrl(
        imageRepoCommand: ImageRepoCommand,
        registryMetadata: RegistryMetadata
    ): String {
        logger.debug("Retrieving type=blob from schemaVersion=v2 url=${registryMetadata.fullRegistryUrl} image=${imageRepoCommand.manifestRepo}")
        return "${registryMetadata.fullRegistryUrl}/{imageGroup}/{imageName}/blobs/{digest}"
    }
    fun createUploadUrl(
        imageRepoCommand: ImageRepoCommand,
        registryMetadata: RegistryMetadata
    ): String {
        logger.debug("Retrieving type=upload from schemaVersion=v2 url=${registryMetadata.fullRegistryUrl} image=${imageRepoCommand.manifestRepo}")
        return "${registryMetadata.fullRegistryUrl}/{imageGroup}/{imageName}/blobs/uploads/"
    }
    fun createManifestUrl(
        imageRepoCommand: ImageRepoCommand,
        registryMetadata: RegistryMetadata
    ): String {
        logger.debug("Retrieving type=manifest from schemaVersion=v1 url=${registryMetadata.fullRegistryUrl} image=${imageRepoCommand.manifestRepo}")
        return "${registryMetadata.fullRegistryUrl}/{imageGroup}/{imageName}/manifests/{imageTag}"
    }

    fun createUploadLayerUrl(imageRepoCommand: ImageRepoCommand, registryMetadata: RegistryMetadata): String {
        logger.debug("Retrieving type=uploadLayer from schemaVersion=v2 url=${registryMetadata.fullRegistryUrl} image=${imageRepoCommand.manifestRepo}")
        return "${registryMetadata.fullRegistryUrl}/{imageGroup}/{imageName}/blobs/uploads/{uuid}?digest={digest}"
    }
}