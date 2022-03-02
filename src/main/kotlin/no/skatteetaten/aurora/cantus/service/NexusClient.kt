package no.skatteetaten.aurora.cantus.service

import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

@Component
class NexusClient(webClientBuilder: WebClient.Builder) {

    private val client = webClientBuilder
        .baseUrl("https://container-nexus.sits.no")
        .defaultHeaders { it.setBearerAuth("") }
        .build()

    fun getVersions(namespace: String, name: String, continuationToken: String?): Mono<NexusSearchResponse> {
        val continuationQuery = if (continuationToken != null) "&continuationToken=$continuationToken" else ""

        return client
            .get()
            .uri("/service/rest/v1/search?name=$namespace/$name&sort=version&repository=internal-group-private$continuationQuery")
            .retrieve()
            .bodyToMono(NexusSearchResponse::class.java)
            .handleError()
    }

    private fun <T> Mono<T>.handleError(): Mono<T> {
        return this.doOnError {
            throw NexusClientException()
        }
    }
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

class NexusClientException : Exception()
