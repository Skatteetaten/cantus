package no.skatteetaten.aurora.cantus.service

import no.skatteetaten.aurora.cantus.controller.handleError
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono

@Component
class NexusClient(
    webClientBuilder: WebClient.Builder,
    @Value("\${integrations.nexus.url}") nexusUrl: String
) {

    private val client = webClientBuilder.baseUrl(nexusUrl).build()

    fun getVersions(
        imageGroup: String,
        name: String,
        repository: String,
        continuationToken: String?
    ): Mono<NexusSearchResponse> {
        val continuationQuery = if (continuationToken == null) "" else "&continuationToken={continuationToken}"
        return client
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

    fun getImage(repository: String, name: String, version: String, sha256: String): Mono<NexusSearchResponse> {
        return client
            .get()
            .uri(
                "/service/rest/v1/search?name={name}&version={version}&repository={repository}&sha256={sha256}&format=docker",
                repository, name, version, sha256
            )
            .retrieve()
            .bodyToMono(NexusSearchResponse::class.java)
            .handleError(
                imageRepoCommand = null,
                message = "operation=GET_IMAGE_FROM_NEXUS name=$name version=$version repository=$repository sha256=$sha256"
            )
    }
}
