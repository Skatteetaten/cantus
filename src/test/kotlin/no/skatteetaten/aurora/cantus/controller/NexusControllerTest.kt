package no.skatteetaten.aurora.cantus.controller

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import no.skatteetaten.aurora.cantus.service.ImageDto
import no.skatteetaten.aurora.cantus.service.NexusMoveService
import no.skatteetaten.aurora.cantus.service.NexusService
import no.skatteetaten.aurora.cantus.service.Version
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@WebFluxTest(controllers = [NexusController::class])
class NexusControllerTest {

    @MockkBean
    private lateinit var nexusService: NexusService

    @MockkBean
    private lateinit var nexusMoveService: NexusMoveService

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun `Call getVersions recursively and respond with a complete list of versions`() {

        every {
            nexusService.getAllVersions(
                "no_skatteetaten_aurora",
                "test",
                "internal-hosted-release"
            )
        } returns Flux.just(
            Version("test1", "2022-02-22T20:22:02.000+00:00"),
            Version("test2", "2022-02-11T20:22:02.000+00:00"),
            Version("test3", "2022-01-22T10:22:02.000+00:00"),
            Version("test4", "2011-02-22T20:11:02.000+00:00")
        )

        every {
            nexusService.getAllVersions(
                "no_skatteetaten_aurora",
                "test",
                "internal-hosted-snapshot"
            )
        } returns Flux.just(
            Version("test5", "2011-02-11T20:11:02.000+00:00"),
            Version("test6", "2011-01-22T10:11:02.000+00:00")
        )

        webTestClient
            .get()
            .uri("/versions?imageGroup=no_skatteetaten_aurora&name=test")
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
    fun `Respond with error status 500 when getAllVersions throws a CantusException`() {

        every {
            nexusService.getAllVersions(
                "no_skatteetaten_aurora",
                "test",
                "internal-hosted-release"
            )
        } throws CantusException("Test error message")

        webTestClient
            .get()
            .uri("/versions?imageGroup=no_skatteetaten_aurora&name=test")
            .exchange()
            .expectStatus().is5xxServerError
            .expectBody()
            .jsonPath("$.status").isEqualTo(500)
    }

    @Test
    fun `Call moveImage with empty sha256`() {

        webTestClient
            .post()
            .uri("/image/move")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                MoveImageCommand(
                    fromRepo = "internal-hosted-client",
                    toRepo = "internal-hosted-release",
                    name = "no_skatteetaten_aurora_demo/whoami",
                    version = null,
                    sha256 = ""
                )
            )
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `Call moveImage with too many matches`() {

        every {
            nexusMoveService.getSingleImage(
                "internal-hosted-client",
                "no_skatteetaten_aurora_demo/whoami",
                null,
                "somesha"
            )
        } returns Mono.error(CantusException("Got too many matches when expecting single match"))

        webTestClient
            .post()
            .uri("/image/move")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                MoveImageCommand(
                    fromRepo = "internal-hosted-client",
                    toRepo = "internal-hosted-release",
                    name = "no_skatteetaten_aurora_demo/whoami",
                    version = null,
                    sha256 = "somesha"
                )
            )
            .exchange()
            .expectStatus().is5xxServerError
            .expectBody()
            .jsonPath("$.name").isEqualTo("no_skatteetaten_aurora_demo/whoami")
            .jsonPath("$.version").isEqualTo("")
            .jsonPath("$.repository").isEqualTo("internal-hosted-client")
            .jsonPath("$.sha256").isEqualTo("somesha")
    }

    @Test
    fun `Call moveImage with success`() {

        every {
            nexusMoveService.getSingleImage(
                "internal-hosted-client",
                "no_skatteetaten_aurora_demo/whoami",
                "2.7.3",
                "sha256_testsha"
            )
        } returns Mono.just(
            ImageDto(
                repository = "internal-hosted-client",
                name = "no_skatteetaten_aurora_demo/whoami",
                version = "2.7.3",
                sha256 = "sha256_testsha"
            )
        )

        every {
            nexusMoveService.moveImage(
                "internal-hosted-client",
                "internal-hosted-release",
                "no_skatteetaten_aurora_demo/whoami",
                "2.7.3",
                "sha256_testsha"
            )
        } returns Mono.just(
            ImageDto(
                repository = "internal-hosted-release",
                name = "no_skatteetaten_aurora_demo/whoami",
                version = "2.7.3",
                sha256 = "sha256_testsha"

            )
        )

        webTestClient
            .post()
            .uri("/image/move")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                MoveImageCommand(
                    fromRepo = "internal-hosted-client",
                    toRepo = "internal-hosted-release",
                    name = "no_skatteetaten_aurora_demo/whoami",
                    version = "2.7.3",
                    sha256 = "sha256_testsha"
                )
            )
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.name").isEqualTo("no_skatteetaten_aurora_demo/whoami")
            .jsonPath("$.version").isEqualTo("2.7.3")
            .jsonPath("$.repository").isEqualTo("internal-hosted-release")
            .jsonPath("$.sha256").isEqualTo("sha256_testsha")
    }

    @Test
    fun `Call moveImage failing to move image`() {

        every {
            nexusMoveService.getSingleImage(
                "internal-hosted-client",
                null,
                null,
                "sha256_testsha"
            )
        } returns Mono.just(
            ImageDto(
                repository = "internal-hosted-client",
                name = "no_skatteetaten_aurora_demo/whoami",
                version = "2.7.3",
                sha256 = "sha256_testsha"
            )
        )

        every {
            nexusMoveService.moveImage(
                "internal-hosted-client",
                "internal-hosted-release",
                "no_skatteetaten_aurora_demo/whoami",
                "2.7.3",
                "sha256_testsha"
            )
        } returns Mono.error(CantusException("Error when moving image: Error from Nexus. Status: Fail"))

        webTestClient
            .post()
            .uri("/image/move")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                MoveImageCommand(
                    fromRepo = "internal-hosted-client",
                    toRepo = "internal-hosted-release",
                    name = null,
                    version = null,
                    sha256 = "sha256_testsha"
                )
            )
            .exchange()
            .expectStatus().is5xxServerError
            .expectBody()
            .jsonPath("$.name").isEqualTo("no_skatteetaten_aurora_demo/whoami")
            .jsonPath("$.version").isEqualTo("2.7.3")
            .jsonPath("$.repository").isEqualTo("internal-hosted-client")
            .jsonPath("$.sha256").isEqualTo("sha256_testsha")
    }

    @Test
    fun `Call moveImage failing to find any matching image to move`() {

        every {
            nexusMoveService.getSingleImage(
                "internal-hosted-client",
                "no_skatteetaten_aurora_demo/whoami",
                null,
                "sha256_nomatch"
            )
        } returns Mono.empty()

        webTestClient
            .post()
            .uri("/image/move")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                MoveImageCommand(
                    fromRepo = "internal-hosted-client",
                    toRepo = "internal-hosted-release",
                    name = "no_skatteetaten_aurora_demo/whoami",
                    version = null,
                    sha256 = "sha256_nomatch"
                )
            )
            .exchange()
            .expectStatus().isNotFound
    }
}
