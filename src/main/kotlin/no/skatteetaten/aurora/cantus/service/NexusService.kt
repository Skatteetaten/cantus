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
            .flatMapIterable { response ->
                response
                    .items
                    .mapNotNull { item -> item.assets[0].lastModified?.let { Version(item.version, it) } }
            }
}
