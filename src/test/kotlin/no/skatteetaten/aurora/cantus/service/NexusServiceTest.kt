package no.skatteetaten.aurora.cantus.service

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
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
    private lateinit var nexusRepository: NexusRepository

    @Autowired
    private lateinit var nexusService: NexusService

    @Test
    fun `Call getVersions recursively and respond with a complete list of versions`() {

        every {
            nexusRepository.getVersions(
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
            nexusRepository.getVersions(
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
            nexusRepository.getVersions(
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
            nexusRepository.getVersions(
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
}

fun getNexusItem(name: String, version: String, lastModified: String) = NexusItem(
    id = "id_$name",
    repository = "repo",
    name = name,
    version = version,
    assets = listOf(NexusAsset("repo", "docker", NexusCheckSum("sha1_$name", "sha256_$name"), lastModified))
)
