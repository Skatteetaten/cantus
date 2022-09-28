package no.skatteetaten.aurora.cantus.service

import no.skatteetaten.aurora.cantus.ServiceTypes
import no.skatteetaten.aurora.cantus.TargetService
import no.skatteetaten.aurora.cantus.controller.CantusException
import no.skatteetaten.aurora.cantus.controller.handleError
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Repository
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono

@Repository
/**
 * NexusRepository is a DDD Repository for CRUD operations against Nexus
 */
class NexusRepository(
    @TargetService(ServiceTypes.NEXUS) val webClientNexus: WebClient,
    @TargetService(ServiceTypes.NEXUS_TOKEN) val webClientNexusToken: WebClient
) {

    fun getVersions(
        imageGroup: String,
        name: String,
        repository: String,
        continuationToken: String?
    ): Mono<NexusSearchResponse> {
        val continuationQuery = if (continuationToken == null) "" else "&continuationToken={continuationToken}"
        return webClientNexus
            .get()
            .uri(
                "/service/rest/v1/search?name={imageGroup}/{name}&sort=version&repository={repository}$continuationQuery",
                imageGroup,
                name,
                repository,
                continuationToken
            )
            .retrieve()
            .bodyToMono(NexusSearchResponse::class.java)
            .handleError(
                imageRepoCommand = null,
                message = "operation=GET_VERSIONS_FROM_NEXUS namespace=$imageGroup name=$name repository=$repository continuationToken=$continuationToken"
            )
    }

    fun getImageFromNexus(repository: String, name: String, version: String, sha256: String): Mono<NexusSearchResponse> {
        return webClientNexusToken
            .get()
            .uri(
                "/service/rest/v1/search?repository={repository}&name={name}&version={version}&sha256={sha256}&format=docker",
                repository, name, version, sha256
            )
            .retrieve()
            .bodyToMono(NexusSearchResponse::class.java)
            .handleError(
                imageRepoCommand = null,
                message = "operation=GET_IMAGE_FROM_NEXUS name=$name version=$version repository=$repository sha256=$sha256 format=docker"
            )
    }

    fun moveImageInNexus(
        fromRepository: String,
        toRepository: String,
        name: String,
        version: String,
        sha256: String
    ): Mono<NexusMoveResponse> {
        return webClientNexusToken
            .post()
            .uri(
                "/service/rest/v1/staging/move/{toRepository}?repository={fromRepository}&name={name}&version={version}&sha256={sha256}&format=docker",
                toRepository, fromRepository, name, version, sha256
            )
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .onStatus(HttpStatus::is5xxServerError) {
                it.bodyToMono<String>().defaultIfEmpty("").flatMap { body -> Mono.error(CantusException(body)) }
            }
            .bodyToMono(NexusMoveResponse::class.java)
            .handleError(
                imageRepoCommand = null,
                message = "operation=POST_MOVE_IMAGE_IN_NEXUS name=$name version=$version fromRepository=$fromRepository " +
                    "toRepository=$toRepository sha256=$sha256 format=docker"
            )
    }
}
