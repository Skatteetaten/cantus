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

    private val client = webClientBuilder
        .baseUrl(nexusUrl)
        .defaultHeaders { it.setBearerAuth("") }
        .build()

    fun getVersions(
        namespace: String,
        name: String,
        repository: String,
        continuationToken: String?
    ): Mono<NexusSearchResponse> = client
        .get()
        .uri(
            "/service/rest/v1/search?name={namespace}/{name}&sort=version&repository={repository}{continuationQuery}",
            namespace,
            name,
            repository,
            if (continuationToken != null) "&continuationToken=$continuationToken" else ""
        )
        .retrieve()
        .bodyToMono(NexusSearchResponse::class.java)
        .handleError(
            imageRepoCommand = null,
            message = "operation=GET_VERSIONS_FROM_NEXUS namespace=$namespace name=$name repository=$repository continuationToken=$continuationToken"
        )
}

data class NexusSearchResponse(
    val items: List<NexusItem>,
    val continuationToken: String?
)

data class NexusItem(
    val version: String,
    val assets: List<NexusAsset>
)

data class NexusAsset(
    val lastModified: String
)
