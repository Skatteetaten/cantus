package no.skatteetaten.aurora.cantus.service

import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Service
class NexusService(val nexusClient: NexusClient) {

    fun getAllVersions(imageGroup: String, name: String, repository: String): Flux<Version> =
        nexusClient
            .getVersions(imageGroup, name, repository, null)
            .expand {
                if (it.continuationToken == null) Mono.empty()
                else nexusClient.getVersions(imageGroup, name, repository, it.continuationToken)
            }
            .flatMapIterable { response -> response.items.map { Version(it.version, it.assets[0].lastModified) } }

    fun getImage(fromRepo: String?, name: String?, version: String?, sha256: String?): Mono<ImageDto?> {
        val nexusSearchResponseMono = nexusClient.getImage(fromRepo ?: "", name ?: "", version ?: "", sha256 ?: "")
        return nexusSearchResponseMono.flatMap {
            if (it.items.size != 1) Mono.empty()
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
}
