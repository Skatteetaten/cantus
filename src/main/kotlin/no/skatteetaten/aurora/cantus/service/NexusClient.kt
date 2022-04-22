package no.skatteetaten.aurora.cantus.service

import no.skatteetaten.aurora.cantus.controller.handleError
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
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
}
