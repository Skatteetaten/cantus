package no.skatteetaten.aurora.cantus.service

import org.springframework.http.HttpStatus
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

    fun getSingleImage(fromRepo: String, name: String?, version: String?, sha256: String?): Mono<SingleImageResponse> {
        val nexusSearchResponseMono = nexusClient.getImage(fromRepo ?: "", name ?: "", version ?: "", sha256 ?: "")
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

    fun moveImage(
        fromRepo: String,
        toRepo: String,
        name: String?,
        version: String?,
        sha256: String?
    ): Mono<MoveImageResponse> {
        return nexusClient.moveImage(fromRepo ?: "", toRepo ?: "", name ?: "", version ?: "", sha256 ?: "")
            .flatMap {
                if (HttpStatus.valueOf(it.status).is2xxSuccessful()) Mono.just(
                    MoveImageResponse(
                        success = true,
                        message = it.message,
                        image = ImageDto(
                            repository = it.data.destination,
                            name = it.data.componentsMoved!!.first().name,
                            version = it.data.componentsMoved!!.first().version,
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
