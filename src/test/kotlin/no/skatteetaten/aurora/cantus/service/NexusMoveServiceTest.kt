package no.skatteetaten.aurora.cantus.service

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import reactor.core.publisher.Mono

@WebFluxTest(controllers = [NexusMoveServiceReactive::class])
class NexusMoveServiceTest {

    private var nexusRepository: NexusRepository = mockk<NexusRepository>()

    private var nexusMoveService = NexusMoveServiceReactive(nexusRepository)

    @Test
    fun `Call getSingleImage and return single match`() {

        every {
            nexusRepository.getImageFromNexus(
                "internal-hosted-client",
                "no_skatteetaten_aurora_demo/whoami",
                "2.7.3",
                "no_skatteetaten_aurora_demo/whoami"
            )
        } returns Mono.just(
            NexusSearchResponse(
                items = listOf(
                    getNexusItem("no_skatteetaten_aurora_demo/whoami", "2.7.3", "2022-02-22T20:22:02.000+00:00")
                ),
                continuationToken = null
            )
        )

        nexusMoveService
            .getSingleImage("internal-hosted-client", "no_skatteetaten_aurora_demo/whoami", "2.7.3", "no_skatteetaten_aurora_demo/whoami")
            .block()
            .let {
                assertThat(it).isNotNull()
                assertThat(it!!.success).isTrue()
                assertThat(it.message).isEqualTo("Got exactly one matching image")
                assertThat(it.image).isNotNull()
                assertThat(it.image!!.name).isEqualTo("no_skatteetaten_aurora_demo/whoami")
                assertThat(it.image!!.repository).isEqualTo("repo")
                assertThat(it.image!!.version).isEqualTo("2.7.3")
                assertThat(it.image!!.sha256).isEqualTo("sha256_no_skatteetaten_aurora_demo/whoami")
            }
    }

    @Test
    fun `Call getSingleImage and return error when no match`() {

        every {
            nexusRepository.getImageFromNexus(
                "internal-hosted-client",
                "no_skatteetaten_aurora_demo/whoami",
                "4.5.6",
                "nothingsha"
            )
        } returns Mono.just(
            NexusSearchResponse(
                items = emptyList(),
                continuationToken = null
            )
        )

        nexusMoveService
            .getSingleImage("internal-hosted-client", "no_skatteetaten_aurora_demo/whoami", "4.5.6", "nothingsha")
            .block()
            .let {
                assertThat(it).isNotNull()
                assertThat(it!!.success).isFalse()
                assertThat(it.message).isEqualTo("Found no matching image")
                assertThat(it.image).isNull()
            }
    }

    @Test
    fun `Call getSingleImage and return error when several matches`() {

        every {
            nexusRepository.getImageFromNexus(
                "internal-hosted-client",
                "no_skatteetaten_aurora_demo/whoami",
                "",
                "somesha"
            )
        } returns Mono.just(
            NexusSearchResponse(
                items = emptyList(),
                continuationToken = null
            )
        )

        every {
            nexusRepository.getImageFromNexus(
                "internal-hosted-client",
                "no_skatteetaten_aurora_demo/whoami",
                "",
                "somesha"
            )
        } returns Mono.just(
            NexusSearchResponse(
                items = listOf(
                    getNexusItem("no_skatteetaten_aurora_demo/whoami", "test1", "2022-02-22T20:22:02.000+00:00"),
                    getNexusItem("no_skatteetaten_aurora_demo/whoami", "test2", "2022-02-11T20:22:02.000+00:00")
                ),
                continuationToken = "cont_token"
            )
        )

        nexusMoveService
            .getSingleImage("internal-hosted-client", "no_skatteetaten_aurora_demo/whoami", null, "somesha")
            .block()
            .let {
                assertThat(it).isNotNull()
                assertThat(it!!.success).isFalse()
                assertThat(it.message).isEqualTo("Got too many matches when expecting single match")
                assertThat(it.image).isNull()
            }
    }

    @Test
    fun `Call moveImage successfully`() {

        every {
            nexusRepository.moveImageInNexus(
                "internal-hosted-client",
                "internal-hosted-release",
                "no_skatteetaten_aurora_demo/whoami",
                "2.7.3",
                "shalala"
            )
        } returns Mono.just(
            NexusMoveResponse(
                status = 200,
                message = "Move Successful",
                data = NexusMoveResponseData(
                    destination = "internal-hosted-release",
                    componentsMoved = listOf(
                        NexusComponentMoved(
                            name = "no_skatteetaten_aurora_demo/whoami",
                            version = "2.7.3",
                            id = "The_ID"
                        )
                    )
                )
            )
        )

        nexusMoveService
            .moveImage(
                "internal-hosted-client",
                "internal-hosted-release",
                "no_skatteetaten_aurora_demo/whoami",
                "2.7.3",
                "shalala"
            )
            .block()
            .let {
                assertThat(it).isNotNull()
                assertThat(it!!.success).isTrue()
                assertThat(it.message).isEqualTo("Move Successful")
                assertThat(it.image).isNotNull()
                assertThat(it.image!!.name).isEqualTo("no_skatteetaten_aurora_demo/whoami")
                assertThat(it.image!!.repository).isEqualTo("internal-hosted-release")
                assertThat(it.image!!.version).isEqualTo("2.7.3")
                assertThat(it.image!!.sha256).isEqualTo("shalala")
            }
    }

    @Test
    fun `Call moveImage failing with not found`() {

        every {
            nexusRepository.moveImageInNexus(
                "internal-hosted-client",
                "internal-hosted-release",
                "no_skatteetaten_aurora_demo/whoami",
                "4.5.6",
                "shanot"
            )
        } returns Mono.just(
            NexusMoveResponse(
                status = 404,
                message = "No components found",
                data = NexusMoveResponseData(
                    destination = "internal-hosted-release",
                    componentsMoved = emptyList()
                )
            )
        )

        nexusMoveService
            .moveImage(
                "internal-hosted-client",
                "internal-hosted-release",
                "no_skatteetaten_aurora_demo/whoami",
                "4.5.6",
                "shanot"
            )
            .block()
            .let {
                assertThat(it).isNotNull()
                assertThat(it!!.success).isFalse()
                assertThat(it.message).isEqualTo("No components found")
                assertThat(it.image).isNull()
            }
    }
}
