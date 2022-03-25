package no.skatteetaten.aurora.cantus.controller

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import no.skatteetaten.aurora.cantus.service.NexusAsset
import no.skatteetaten.aurora.cantus.service.NexusClient
import no.skatteetaten.aurora.cantus.service.NexusItem
import no.skatteetaten.aurora.cantus.service.NexusSearchResponse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono

@WebFluxTest(controllers = [NexusController::class])
class NexusControllerTest {

    @MockkBean
    private lateinit var nexusClient: NexusClient

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun `Call getVersions recursively and respond with a complete list of versions`() {

        every {
            nexusClient.getVersions(
                "no_skatteetaten_aurora",
                "test",
                "internal-hosted-release",
                null
            )
        } returns Mono.just(
            NexusSearchResponse(
                items = listOf(
                    NexusItem(
                        version = "test1",
                        assets = listOf(NexusAsset("2022-02-22T20:22:02.000+00:00"))
                    ),
                    NexusItem(
                        version = "test2",
                        assets = listOf(NexusAsset("2022-02-11T20:22:02.000+00:00"))
                    )
                ),
                continuationToken = "ct1"
            )
        )

        every {
            nexusClient.getVersions(
                "no_skatteetaten_aurora",
                "test",
                "internal-hosted-release",
                "ct1"
            )
        } returns Mono.just(
            NexusSearchResponse(
                items = listOf(
                    NexusItem(
                        version = "test3",
                        assets = listOf(NexusAsset("2022-01-22T10:22:02.000+00:00"))
                    )
                ),
                continuationToken = null
            )
        )

        every {
            nexusClient.getVersions(
                "no_skatteetaten_aurora",
                "test",
                "internal-hosted-snapshot",
                null
            )
        } returns Mono.just(
            NexusSearchResponse(
                items = listOf(
                    NexusItem(
                        version = "test4",
                        assets = listOf(NexusAsset("2011-02-22T20:11:02.000+00:00"))
                    ),
                    NexusItem(
                        version = "test5",
                        assets = listOf(NexusAsset("2011-02-11T20:11:02.000+00:00"))
                    )
                ),
                continuationToken = "ct2"
            )
        )

        every {
            nexusClient.getVersions(
                "no_skatteetaten_aurora",
                "test",
                "internal-hosted-snapshot",
                "ct2"
            )
        } returns Mono.just(
            NexusSearchResponse(
                items = listOf(
                    NexusItem(
                        version = "test6",
                        assets = listOf(NexusAsset("2011-01-22T10:11:02.000+00:00"))
                    )
                ),
                continuationToken = null
            )
        )

        webTestClient
            .get()
            .uri("/versions?namespace=no_skatteetaten_aurora&name=test")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.*").isArray
            .jsonPath("$[0].name").isEqualTo("test1")
            .jsonPath("$[0].lastModified").isEqualTo("2022-02-22T20:22:02.000+00:00")
            .jsonPath("$[1].name").isEqualTo("test2")
            .jsonPath("$[1].lastModified").isEqualTo("2022-02-11T20:22:02.000+00:00")
            .jsonPath("$[2].name").isEqualTo("test3")
            .jsonPath("$[2].lastModified").isEqualTo("2022-01-22T10:22:02.000+00:00")
            .jsonPath("$[3].name").isEqualTo("test4")
            .jsonPath("$[3].lastModified").isEqualTo("2011-02-22T20:11:02.000+00:00")
            .jsonPath("$[4].name").isEqualTo("test5")
            .jsonPath("$[4].lastModified").isEqualTo("2011-02-11T20:11:02.000+00:00")
            .jsonPath("$[5].name").isEqualTo("test6")
            .jsonPath("$[5].lastModified").isEqualTo("2011-01-22T10:11:02.000+00:00")
    }

    @Test
    fun `Respond with error status 500 when getVersions returns a CantusException`() {

        every {
            nexusClient.getVersions(
                "no_skatteetaten_aurora",
                "test",
                "internal-hosted-release",
                null
            )
        } throws CantusException("Test error message")

        webTestClient
            .get()
            .uri("/versions?namespace=no_skatteetaten_aurora&name=test")
            .exchange()
            .expectStatus().is5xxServerError
            .expectBody()
            .jsonPath("$.status").isEqualTo(500)
    }
}
