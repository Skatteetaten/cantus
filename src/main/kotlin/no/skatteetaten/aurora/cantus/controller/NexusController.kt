package no.skatteetaten.aurora.cantus.controller

import no.skatteetaten.aurora.cantus.service.NexusService
import no.skatteetaten.aurora.cantus.service.Version
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux

const val RELEASE_REPO = "internal-hosted-release"
const val SNAPSHOT_REPO = "internal-hosted-snapshot"

@RestController
class NexusController(val nexusService: NexusService) {

    @GetMapping("/versions")
    fun getVersions(
        @RequestParam imageGroup: String,
        @RequestParam name: String
    ): Flux<Version> {
        val releaseVersions = nexusService.getAllVersions(imageGroup, name, RELEASE_REPO)
        val snapshotVersions = nexusService.getAllVersions(imageGroup, name, SNAPSHOT_REPO)
        return Flux.concat(releaseVersions, snapshotVersions)
    }
}
