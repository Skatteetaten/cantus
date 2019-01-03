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
        val simplifiedManifest = dockerRegistryService.getImageManifest("$affiliation/$name", tag, dockerRegistryUrl)
        if (simplifiedManifest.isEmpty()) throw NoSuchResourceException("Kunne ikke finne manifestet til image $affiliation/$name")
        return simplifiedManifest
    }

    @GetMapping("/affiliation/{affiliation}/name/{name}/tags")
    fun getImageTags(
            @PathVariable affiliation: String,
            @PathVariable name: String,
            @RequestParam(required = false) dockerRegistryUrl: String?
    ): List<String> {
        val tags = dockerRegistryService.getImageTags("$affiliation/$name", dockerRegistryUrl)
        if (tags.isEmpty()) throw NoSuchResourceException("Fant ikke tags for $affiliation/$name")
        return tags
    }

    @GetMapping("/affiliation/{affiliation}/name/{name}/tags/groupBy/semanticVersion")
    fun getImageTagsGroupedBySemanticVersion(
            @PathVariable affiliation: String,
            @PathVariable name: String,
            @RequestParam(required = false) dockerRegistryUrl: String?
    ): Map<String, List<String>> {
        val tagsGroupedBySemVersion = dockerRegistryService.getImageTagsGroupedBySemanticVersion("$affiliation/$name", dockerRegistryUrl)
        if (tagsGroupedBySemVersion.isNullOrEmpty()) throw NoSuchResourceException("Kan ikke gruppere tags. Fant ingen tags for $affiliation/$name")
        return tagsGroupedBySemVersion
    }
}