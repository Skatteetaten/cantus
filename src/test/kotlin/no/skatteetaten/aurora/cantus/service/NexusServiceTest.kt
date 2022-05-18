package no.skatteetaten.aurora.cantus.service

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import no.skatteetaten.aurora.cantus.controller.CantusException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import reactor.core.publisher.Mono

@WebFluxTest(controllers = [NexusService::class])
class NexusServiceTest {

    @MockkBean
    private lateinit var nexusClient: NexusClient

    @Autowired
    private lateinit var nexusService: NexusService

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
                    getNexusItem("name1", "test1", "2022-02-22T20:22:02.000+00:00"),
                    getNexusItem("name2", "test2", "2022-02-11T20:22:02.000+00:00")
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
                    getNexusItem("name3", "test3", "2022-01-22T10:22:02.000+00:00")
                ),
                continuationToken = "ct2"
            )
        )

        every {
            nexusClient.getVersions(
                "no_skatteetaten_aurora",
                "test",
                "internal-hosted-release",
                "ct2"
            )
        } returns Mono.just(
            NexusSearchResponse(
                items = listOf(
                    getNexusItem("name4", "test4", "2011-02-22T20:11:02.000+00:00")
                ),
                continuationToken = null
            )
        )

        nexusService
            .getAllVersions("no_skatteetaten_aurora", "test", "internal-hosted-release")
            .collectList()
            .block()
            .let {
                assertThat(it).isNotNull()
                assertThat(it!!.size).isEqualTo(4)
                assertThat(it[0].name).isEqualTo("test1")
                assertThat(it[0].lastModified).isEqualTo("2022-02-22T20:22:02.000+00:00")
                assertThat(it[1].name).isEqualTo("test2")
                assertThat(it[1].lastModified).isEqualTo("2022-02-11T20:22:02.000+00:00")
                assertThat(it[2].name).isEqualTo("test3")
                assertThat(it[2].lastModified).isEqualTo("2022-01-22T10:22:02.000+00:00")
                assertThat(it[3].name).isEqualTo("test4")
                assertThat(it[3].lastModified).isEqualTo("2011-02-22T20:11:02.000+00:00")
            }
    }

    @Test
    fun `Throw CantusException from getVersions`() {

        every {
            nexusClient.getVersions(
                "no_skatteetaten_aurora",
                "test",
                "internal-hosted-release",
                null
            )
        } throws CantusException("Lorem ipsum dolor sit amet")

        assertThrows<CantusException> {
            nexusService.getAllVersions(
                "no_skatteetaten_aurora",
                "test",
                "internal-hosted-release"
            )
        }.run { assertThat(message).isEqualTo("Lorem ipsum dolor sit amet") }
    }

    @Test
    fun `Call getSingleImage and return single match`() {

        every {
            nexusClient.getImage(
                "internal-hosted-client",
                "no_skatteetaten_aurora_demo/whoami",
                "2.7.3",
                ""
            )
        } returns Mono.just(
            NexusSearchResponse(
                items = listOf(
                    getNexusItem("no_skatteetaten_aurora_demo/whoami", "2.7.3", "2022-02-22T20:22:02.000+00:00")
                ),
                continuationToken = null
            )
        )

        nexusService
            .getSingleImage("internal-hosted-client", "no_skatteetaten_aurora_demo/whoami", "2.7.3", null)
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
            nexusClient.getImage(
                "internal-hosted-client",
                "no_skatteetaten_aurora_demo/whoami",
                "4.5.6",
                ""
            )
        } returns Mono.just(
            NexusSearchResponse(
                items = emptyList(),
                continuationToken = null
            )
        )

        nexusService
            .getSingleImage("internal-hosted-client", "no_skatteetaten_aurora_demo/whoami", "4.5.6", null)
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
            nexusClient.getImage(
                "internal-hosted-client",
                "no_skatteetaten_aurora_demo/whoami",
                "",
                ""
            )
        } returns Mono.just(
            NexusSearchResponse(
                items = emptyList(),
                continuationToken = null
            )
        )

        every {
            nexusClient.getImage(
                "internal-hosted-client",
                "no_skatteetaten_aurora_demo/whoami",
                "",
                ""
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

        nexusService
            .getSingleImage("internal-hosted-client", "no_skatteetaten_aurora_demo/whoami", null, null)
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
            nexusClient.moveImage(
                "internal-hosted-client",
                "internal-hosted-release",
                "no_skatteetaten_aurora_demo/whoami",
                "2.7.3",
                ""
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

        nexusService
            .moveImage(
                "internal-hosted-client",
                "internal-hosted-release",
                "no_skatteetaten_aurora_demo/whoami",
                "2.7.3",
                null
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
            }
    }

    @Test
    fun `Call moveImage failing with not found`() {

        every {
            nexusClient.moveImage(
                "internal-hosted-client",
                "internal-hosted-release",
                "no_skatteetaten_aurora_demo/whoami",
                "4.5.6",
                ""
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

        nexusService
            .moveImage(
                "internal-hosted-client",
                "internal-hosted-release",
                "no_skatteetaten_aurora_demo/whoami",
                "4.5.6",
                null
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

fun getNexusItem(name: String, version: String, lastModified: String) = NexusItem(
    id = "id_$name",
    repository = "repo",
    name = name,
    version = version,
    assets = listOf(NexusAsset("repo", "docker", NexusCheckSum("sha1_$name", "sha256_$name"), lastModified))
)
