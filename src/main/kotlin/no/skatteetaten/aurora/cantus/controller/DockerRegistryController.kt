package no.skatteetaten.aurora.cantus.controller

import com.fasterxml.jackson.databind.JsonNode
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
    ): JsonNode? {
        return dockerRegistryService.getImageManifest(dockerRegistryUrl, "$affiliation/$name", tag)
    }

    @GetMapping("/affiliation/{affiliation}/name/{name}/tags")
    fun getImageTags(
        @PathVariable affiliation: String,
        @PathVariable name: String,
        @RequestParam(required = false) dockerRegistryUrl: String?
    ): JsonNode {
        return dockerRegistryService.getImageTags(dockerRegistryUrl, "$affiliation/$name")
    }

    @GetMapping("/affiliation/{affiliation}/name/{name}/tags/groupBy/semanticVersion")
    fun getImageTagsGroupedBySemanticVersion(
        @PathVariable affiliation: String,
        @PathVariable name: String,
        @RequestParam(required = false) dockerRegistryUrl: String?
    ): JsonNode {
        return dockerRegistryService.getImageTagsGroupedBySemanticVersion(dockerRegistryUrl, "$affiliation/$name")
    }
}