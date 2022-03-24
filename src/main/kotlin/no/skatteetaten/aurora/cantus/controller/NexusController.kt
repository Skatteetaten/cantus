package no.skatteetaten.aurora.cantus.controller

import no.skatteetaten.aurora.cantus.service.NexusClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

data class Version(
    val name: String,
    val lastModified: String
)

@RestController
class NexusController(val nexusClient: NexusClient) {

    @GetMapping("/versions")
    fun getVersions(
        @RequestParam namespace: String,
        @RequestParam name: String
    ): Flux<Version> {

        val repositories = listOf("internal-hosted-release", "internal-hosted-snapshot")
        var repositoryIndex = 0

        return nexusClient
            .getVersions(namespace, name, repositories[0], null)
            .expand {
                if (it.continuationToken == null) repositoryIndex += 1
                if (repositoryIndex == repositories.size) Mono.empty()
                else nexusClient.getVersions(namespace, name, repositories[repositoryIndex], it.continuationToken)
            }
            .flatMapIterable { response -> response.items.map { Version(it.version, it.assets[0].lastModified) } }
    }
}
