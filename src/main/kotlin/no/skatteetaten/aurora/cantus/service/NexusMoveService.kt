package no.skatteetaten.aurora.cantus.service

import no.skatteetaten.aurora.cantus.RequiresNexusToken
import no.skatteetaten.aurora.cantus.controller.CantusException
import no.skatteetaten.aurora.cantus.controller.IntegrationDisabledException
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

@Service
@ConditionalOnBean(RequiresNexusToken::class)
class NexusMoveServiceReactive(
    private val nexusRepository: NexusRepository
) : NexusMoveService {
    override fun getSingleImage(
        fromRepo: String,
        name: String?,
        version: String?,
        sha256: String
    ): Mono<ImageDto> {
        val nexusSearchResponseMono = nexusRepository.getImageFromNexus(fromRepo, name ?: "", version ?: "", sha256)
        return nexusSearchResponseMono.flatMap {
            if (it.items.size == 0) Mono.empty()
            else if (it.items.size > 1) Mono.error(CantusException("Got too many matches when expecting single match"))
            else with(it.items.first()) {
                Mono.just(
                    ImageDto(
                        repository = this.repository,
                        name = this.name,
                        version = this.version,
                        sha256 = assets.first().checksum.sha256
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
        sha256: String
    ): Mono<ImageDto> {
        return nexusRepository.moveImageInNexus(fromRepo, toRepo, name ?: "", version ?: "", sha256)
            .flatMap {
                if (HttpStatus.valueOf(it.status).is2xxSuccessful) Mono.just(
                    ImageDto(
                        repository = it.data.destination,
                        name = it.data.componentsMoved!!.first().name,
                        version = it.data.componentsMoved.first().version,
                        sha256 = sha256

                    )
                )
                else Mono.error(CantusException("Error when moving image: ${it.message}. Status: ${it.status}"))
            }
    }
}

interface NexusMoveService {

    fun getSingleImage(fromRepo: String, name: String?, version: String?, sha256: String): Mono<ImageDto> =
        integrationDisabled()

    fun moveImage(
        fromRepo: String,
        toRepo: String,
        name: String?,
        version: String?,
        sha256: String
    ): Mono<ImageDto> = integrationDisabled()

    private fun integrationDisabled(): Nothing =
        throw IntegrationDisabledException("Nexus move service is disabled in this environment")
}

@Service
@ConditionalOnMissingBean(RequiresNexusToken::class)
class NexusMoveDisabled : NexusMoveService
