package no.skatteetaten.aurora.cantus.controller

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.cantus.service.DockerRegistryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class DockerRegistryController(val dockerRegistryService: DockerRegistryService) {

    @GetMapping("/group/{group}/name/{name}/tag/{tag}/manifest")
    fun getManifestInformation(
        @PathVariable group: String,
        @PathVariable name: String,
        @PathVariable tag: String,
        @RequestParam(required = false) dockerRegistryUrl: String?
    ): JsonNode? {
        return dockerRegistryService.getImageManifest(dockerRegistryUrl, "$group/$name", tag)
    }
}