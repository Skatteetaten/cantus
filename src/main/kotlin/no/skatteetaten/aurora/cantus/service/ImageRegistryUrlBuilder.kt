package no.skatteetaten.aurora.cantus.service

import no.skatteetaten.aurora.cantus.controller.BadRequestException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder



@Component
class ImageRegistryUrlBuilder(
    @Value("\${cantus.docker.url") val registryUrl: String
) {

    fun createTagsUrl(imageRepoDto: ImageRepoDto, registryMetadata: RegistryMetadata) =
        buildUri(
            registryMetadata.apiSchema,
            "/v2/{imageGroup}/{imageName}/tags/list",
            imageRepoDto.namespace,
            imageRepoDto.name,
            registryAddress = registryUrl
        )

    fun createConfigUrl(imageRepoDto: ImageRepoDto, configDigest: String?, registryMetadata: RegistryMetadata): String? {
        if (configDigest == null) {
            return null
        }

        return buildUri(
            registryMetadata.apiSchema,
            "/v2/{imageGroup}/{imageName}/blobs/sha:256:{configdigest}",
            imageRepoDto.namespace,
            imageRepoDto.name,
            configDigest,
            registryAddress = registryUrl
        )
    }

    fun createManifestUrl(imageRepoDto: ImageRepoDto, registryMetadata: RegistryMetadata): String? {
        if (imageRepoDto.tag == null) {
            return null
        }
        logger.debug("Retrieving manifest from $registryUrl")
        logger.debug("Retrieving manifest for image ${imageRepoDto.namespace}/${imageRepoDto.name}:${imageRepoDto.tag}")

        return buildUri(
            registryMetadata.apiSchema,
            "/v2/{imageGroup}/{imageName}/manifests/{tag}",
            imageRepoDto.namespace,
            imageRepoDto.name,
            imageRepoDto.tag,
            registryAddress = registryUrl
        )
    }

    fun buildUri(apiSchema: String, templateUri: String, vararg templateVars: String, registryAddress: String) =
        UriComponentsBuilder
            .newInstance()
            .scheme(apiSchema)
            .host(registryAddress)
            .path(templateUri)
            .buildAndExpand(templateVars)
            .toUriString()
}

@Component
/**
 * This component is convenient to use if you need to override the registry that is used in the imageRepoMetadata parameter
 * with a hard coded one. For instance during development if the acutal image registry is not available, but the image
 * may be found in a test/dev registry.
 */
class OverrideRegistryImageRegistryUrlBuilder(
    @Value("\${cantus.docker.url}") val defaultUrl: String,
    val allowedRegistryUrls: List<String>,
    val registryUrlOverride: String
) : ImageRegistryUrlBuilder(defaultUrl) {
    override val registryUrl by lazy {
        validateDockerRegistryUrl(
            urlToValidate = registryUrlOverride,
            allowedUrls = allowedRegistryUrls
        )
    }

    private final fun validateDockerRegistryUrl(urlToValidate: String, allowedUrls: List<String>): String {
        if (!allowedUrls.any { allowedUrl -> urlToValidate == allowedUrl }) {
            throw BadRequestException("Invalid Docker Registry URL")
        } else {
            return urlToValidate
        }
    }
}