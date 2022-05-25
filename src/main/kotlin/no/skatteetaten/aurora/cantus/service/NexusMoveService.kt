package no.skatteetaten.aurora.cantus.service

import no.skatteetaten.aurora.cantus.RequiresNexusToken
import no.skatteetaten.aurora.cantus.controller.IntegrationDisabledException
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

@Service
@ConditionalOnBean(RequiresNexusToken::class)
class NexusMoveServiceReactive(private val nexusClient: NexusClient) : NexusMoveService {
    override fun getSingleImage(
        fromRepo: String,
        name: String?,
        version: String?,
        sha256: String?
    ): Mono<SingleImageResponse> {
        val nexusSearchResponseMono = nexusClient.getImage(fromRepo, name ?: "", version ?: "", sha256 ?: "")
        return nexusSearchResponseMono.flatMap {
            if (it.items.size != 1) Mono.just(
                SingleImageResponse(
                    success = false,
                    message = if (it.items.isEmpty()) "Found no matching image" else "Got too many matches when expecting single match",
                    image = null
                )
            )
            else with(it.items.first()) {
                Mono.just(
                    SingleImageResponse(
                        success = true,
                        message = "Got exactly one matching image",
                        image = ImageDto(
                            repository = this.repository,
                            name = this.name,
                            version = this.version,
                            sha256 = assets.first().checksum.sha256
                        )
                    )
                )
            }
        }
    }

    override fun moveImage(
        fromRepo: String,
        toRepo: String,
        name: String?,
        version: String?,
        sha256: String?
    ): Mono<MoveImageResponse> {
        return nexusClient.moveImage(fromRepo, toRepo, name ?: "", version ?: "", sha256 ?: "")
            .flatMap {
                if (HttpStatus.valueOf(it.status).is2xxSuccessful) Mono.just(
                    MoveImageResponse(
                        success = true,
                        message = it.message,
                        image = ImageDto(
                            repository = it.data.destination,
                            name = it.data.componentsMoved!!.first().name,
                            version = it.data.componentsMoved.first().version,
                            sha256 = null
                        )
                    )
                ) else Mono.just(
                    MoveImageResponse(
                        success = false,
                        message = it.message,
                        image = null
                    )
                )
            }
    }
}

interface NexusMoveService {

    fun getSingleImage(fromRepo: String, name: String?, version: String?, sha256: String?): Mono<SingleImageResponse> =
        integrationDisabled()

    fun moveImage(
        fromRepo: String,
        toRepo: String,
        name: String?,
        version: String?,
        sha256: String?
    ): Mono<MoveImageResponse> = integrationDisabled()

    private fun integrationDisabled(): Nothing =
        throw IntegrationDisabledException("Nexus move service is disabled in this environment")
}

@Service
@ConditionalOnMissingBean(RequiresNexusToken::class)
class NexusMoveDisabled : NexusMoveService
