package no.skatteetaten.aurora.cantus.controller

import no.skatteetaten.aurora.cantus.service.NexusClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

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

        fun Version.lastModifiedToInt() = ZonedDateTime
            .parse(lastModified, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            .toEpochSecond()
            .toInt()

        return nexusClient
            .getVersions(namespace, name, null)
            .expand {
                if (it.continuationToken == null) Mono.empty()
                else nexusClient.getVersions(namespace, name, it.continuationToken)
            }
            .flatMapIterable { response -> response.items.map { Version(it.version, it.assets[0].lastModified) } }
            .sort { o1, o2 -> o2.lastModifiedToInt() - o1.lastModifiedToInt() }
    }
}
