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
    suspend fun moveImage(
        @RequestBody moveImageCommand: MoveImageCommand
        // TODO: add code to verify Flyttebil as source
    ): Mono<MoveImageResult> {
        // Search for image and validate that it correspond with exactly one instance in the expected repo
        with(moveImageCommand) {
            return nexusService.getSingleImage(fromRepo, name, version, sha256)
                .flatMap { singleImageResponse ->
                    if (singleImageResponse.success)
                        nexusService.moveImage(fromRepo, toRepo, name, version, sha256)
                            .flatMap {
                                Mono.just(
                                    MoveImageResult(
                                        success = it.success,
                                        message = it.message,
                                        name = name ?: "",
                                        version = version ?: "",
                                        repository = toRepo,
                                        sha256 = sha256 ?: ""
                                    )
                                )
                            }
                    else
                        Mono.just(
                            MoveImageResult(
                                success = false,
                                message = singleImageResponse.message,
                                name = name ?: "",
                                version = version ?: "",
                                repository = toRepo,
                                sha256 = sha256 ?: ""
                            )
                        )
                }
        }
    }
}

data class MoveImageCommand(
    val fromRepo: String,
    val toRepo: String,
    val name: String?,
    val version: String?,
    val sha256: String?
)

data class MoveImageResult(
    val success: Boolean,
    val message: String,
    val name: String,
    val version: String,
    val repository: String,
    val sha256: String
)
