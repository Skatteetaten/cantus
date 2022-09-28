package no.skatteetaten.aurora.cantus.service

import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Service
class NexusService(
    private val nexusRepository: NexusRepository
) {
    fun getAllVersions(imageGroup: String, name: String, repository: String): Flux<Version> =
        nexusRepository.getVersions(imageGroup, name, repository, null)
            .expand {
                if (it.continuationToken == null) Mono.empty()
                else nexusRepository.getVersions(imageGroup, name, repository, it.continuationToken)
            }
            .flatMapIterable { response -> response.items.map { Version(it.version, it.assets[0].lastModified) } }
}
