package no.skatteetaten.aurora.cantus.controller

import mu.KotlinLogging
import no.skatteetaten.aurora.cantus.service.NexusMoveService
import no.skatteetaten.aurora.cantus.service.NexusService
import no.skatteetaten.aurora.cantus.service.Version
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

private val logger = KotlinLogging.logger {}

const val RELEASE_REPO = "internal-hosted-release"
const val SNAPSHOT_REPO = "internal-hosted-snapshot"

@RestController
class NexusController(
    private val nexusService: NexusService,
    private val nexusMoveService: NexusMoveService,
) {

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
        @RequestBody moveImageCmd: MoveImageCommand
        // TODO: add code to verify Flyttebil as source
    ): Mono<MoveImageResult> {
        // Search for image and validate that it correspond with exactly one instance in the expected repo
        if (moveImageCmd.sha256.isEmpty()) return Mono.just(
            MoveImageResult(
                success = false,
                message = "Invalid input: sha256 is mandatory",
                name = moveImageCmd.name ?: "",
                version = moveImageCmd.version ?: "",
                repository = moveImageCmd.fromRepo,
                sha256 = moveImageCmd.sha256
            )
        )
        return nexusMoveService.getSingleImage(
            moveImageCmd.fromRepo,
            moveImageCmd.name,
            moveImageCmd.version,
            moveImageCmd.sha256
        ).flatMap { singleImageResponse ->
            if (singleImageResponse.success)
                nexusMoveService.moveImage(
                    singleImageResponse.image!!.repository,
                    moveImageCmd.toRepo,
                    singleImageResponse.image.name,
                    singleImageResponse.image.version,
                    singleImageResponse.image.sha256
                )
                    .flatMap {
                        if (it.success) {
                            logger.info { "Moved image ${it.image!!.name}:${it.image.version} to ${it.image.repository}" }
                        } else {
                            logger.warn { "Failed to move image ${singleImageResponse.image.name}:${singleImageResponse.image.version}" }
                        }
                        Mono.just(
                            MoveImageResult(
                                success = it.success,
                                message = it.message,
                                name = it.image!!.name,
                                version = it.image.version,
                                repository = it.image.repository,
                                sha256 = it.image.sha256
                            )
                        )
                    }
            else {
                logger.warn { "Could not find single image for search criteria. Message: ${singleImageResponse.message}" }
                Mono.just(
                    MoveImageResult(
                        success = false,
                        message = singleImageResponse.message,
                        name = moveImageCmd.name ?: "",
                        version = moveImageCmd.version ?: "",
                        repository = moveImageCmd.fromRepo,
                        sha256 = moveImageCmd.sha256
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
    val sha256: String
)

data class MoveImageResult(
    val success: Boolean,
    val message: String,
    val name: String,
    val version: String,
    val repository: String,
    val sha256: String
)
