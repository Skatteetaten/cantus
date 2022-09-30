package no.skatteetaten.aurora.cantus.controller

import mu.KotlinLogging
import no.skatteetaten.aurora.cantus.service.ImageDto
import no.skatteetaten.aurora.cantus.service.NexusMoveService
import no.skatteetaten.aurora.cantus.service.NexusService
import no.skatteetaten.aurora.cantus.service.Version
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.switchIfEmpty
import javax.validation.Valid
import javax.validation.constraints.Size

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
        @Valid @RequestBody moveImageCmd: MoveImageCommand
        // TODO: add code to verify Flyttebil as source
    ): Mono<ResponseEntity<MoveImageResult>> {
        // Search for image and validate that it correspond with exactly one instance in the expected repo
        return nexusMoveService.getSingleImage(
            moveImageCmd.fromRepo,
            moveImageCmd.name,
            moveImageCmd.version,
            moveImageCmd.sha256
        )
            .flatMap { singleImageDto ->
                nexusMoveService.moveImage(
                    singleImageDto!!.repository,
                    moveImageCmd.toRepo,
                    singleImageDto.name,
                    singleImageDto.version,
                    singleImageDto.sha256
                )
                    .flatMap { movedImageDto ->
                        logger.info { "Moved image ${movedImageDto.name}:${movedImageDto.version} to ${movedImageDto.repository}" }
                        Mono.just(ResponseEntity.ok().body(moveImageResultFromImageDto(movedImageDto)))
                    }
                    .doOnError {
                        logger.error { "Failed to move image ${singleImageDto.name}:${singleImageDto.version} with sha ${singleImageDto.sha256}" }
                        Mono.just(
                            ResponseEntity.internalServerError().body(moveImageResultFromImageDto(singleImageDto))
                        )
                    }
            }
            .switchIfEmpty {
                logger.info { "Found no image for search criteria" }
                Mono.just(
                    ResponseEntity.ok().body(moveImageResultFromMoveImageCommand(moveImageCmd))
                )
            }
            .doOnError { e -> logger.error("Error when searching for single image", e) }
            .onErrorResume { e ->
                Mono.just(
                    ResponseEntity.internalServerError().body(moveImageResultFromMoveImageCommand(moveImageCmd))
                )
            }
    }

    private fun moveImageResultFromImageDto(movedImageDto: ImageDto) = MoveImageResult(
        name = movedImageDto.name,
        version = movedImageDto.version,
        repository = movedImageDto.repository,
        sha256 = movedImageDto.sha256
    )

    private fun moveImageResultFromMoveImageCommand(moveImageCmd: MoveImageCommand) = MoveImageResult(
        name = moveImageCmd.name ?: "",
        version = moveImageCmd.version ?: "",
        repository = moveImageCmd.fromRepo,
        sha256 = moveImageCmd.sha256
    )
}

data class MoveImageCommand(
    @field:Size(min = 1)
    val fromRepo: String,
    @field:Size(min = 1)
    val toRepo: String,
    val name: String?,
    val version: String?,
    @field:Size(min = 6)
    val sha256: String
)

data class MoveImageResult(
    val name: String,
    val version: String,
    val repository: String,
    val sha256: String
)
