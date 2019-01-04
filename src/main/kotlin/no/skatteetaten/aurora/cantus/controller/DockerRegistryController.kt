package no.skatteetaten.aurora.cantus.controller

import no.skatteetaten.aurora.cantus.service.DockerRegistryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class DockerRegistryController(val dockerRegistryService: DockerRegistryService) {

    @GetMapping("/affiliation/{affiliation}/name/{name}/tag/{tag}/manifest")
    fun getManifestInformation(
        @PathVariable affiliation: String,
        @PathVariable name: String,
        @PathVariable tag: String,
        @RequestParam(required = false) dockerRegistryUrl: String?
    ): Map<String, String> {
        return dockerRegistryService
            .getImageManifestInformation("$affiliation/$name", tag, dockerRegistryUrl)
            .ifEmpty { throw NoSuchResourceException("Kunne ikke finne manifestet til image $affiliation/$name") }
    }

    @GetMapping("/affiliation/{affiliation}/name/{name}/tags")
    fun getImageTags(
        @PathVariable affiliation: String,
        @PathVariable name: String,
        @RequestParam(required = false) dockerRegistryUrl: String?
    ) =
        dockerRegistryService.getImageTags("$affiliation/$name", dockerRegistryUrl)
            .ifEmpty { throw NoSuchResourceException("Fant ikke tags for $affiliation/$name") }

    @GetMapping("/affiliation/{affiliation}/name/{name}/tags/semantic")
    fun getImageTagsSemantic(
        @PathVariable affiliation: String,
        @PathVariable name: String,
        @RequestParam(required = false) dockerRegistryUrl: String?
    ) =
        dockerRegistryService.getImageTagsGroupedBySemanticVersion("$affiliation/$name", dockerRegistryUrl)
            .ifEmpty { throw NoSuchResourceException("Kan ikke gruppere tags. Fant ingen tags for $affiliation/$name") }
}