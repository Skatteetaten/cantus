package no.skatteetaten.aurora.cantus.controller

import no.skatteetaten.aurora.cantus.service.NexusService
import no.skatteetaten.aurora.cantus.service.Version
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

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

    @PostMapping("/image/move")
    fun moveImage(
        @RequestBody moveImageCommand: MoveImageCommand
        // TODO: add code to verify Flyttebil as source
    ): Mono<MoveImageResult> {
        // Search for image and validate that it correspond with exactly one instance in the expected repo
        val image = with(moveImageCommand) {
            nexusService.getImage(fromRepo, name, version, sha256)
        }

        // Move if move command seems valid

        val moveImageResult = MoveImageResult(
            true,
            moveImageCommand.name ?: "",
            moveImageCommand.version ?: "",
            moveImageCommand.toRepo,
            moveImageCommand.sha256 ?: ""
        )

        return Mono.just(moveImageResult)
    }
}

data class MoveImageCommand(
    val fromRepo: String,
    val toRepo: String,
    val name: String?,
    val version: String?,
    val sha256: String?
)

data class MoveImageResult(val success: Boolean, val name: String, val version: String, val repository: String, val sha256: String)
