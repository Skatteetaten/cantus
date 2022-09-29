package no.skatteetaten.aurora.cantus.service

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNullOrEmpty
import no.skatteetaten.aurora.cantus.MoveImageConfig
import no.skatteetaten.aurora.cantus.controller.CantusException
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.execute
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.setJsonFileAsBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.reactive.function.client.WebClient

class NexusRepositoryTest {

    private val server = MockWebServer()
    private val mockWebClient: WebClient = WebClient.builder().baseUrl(server.url("/").toString()).build()
    private val nexusRepository = NexusRepository(mockWebClient, mockWebClient, MoveImageConfig("true"))
    private val nexusRepositoryMoveInactive = NexusRepository(mockWebClient, mockWebClient, MoveImageConfig("false"))

    @Test
    fun `Parse response from the Nexus API getVersions`() {

        val response = MockResponse()
            .setJsonFileAsBody("nexusClientSearchResponseGetVersions.json")
            .addHeader("Content-Type", "application/json")

        server.execute(response) {
            nexusRepository
                .getVersions("no_skatteetaten_aurora", "test", "test", null)
                .block()
                .run {
                    assertThat(this).isNotNull()
                    assertThat(this!!.items).isNotNull()
                    assertThat(items).hasSize(3)
                    assertThat(items[0].version).isEqualTo("1")
                    assertThat(items[0].assets).hasSize(1)
                    assertThat(items[0].assets[0].lastModified).isEqualTo("2021-11-17T08:45:45.104+00:00")
                    assertThat(items[1].version).isEqualTo("2")
                    assertThat(items[1].assets).hasSize(1)
                    assertThat(items[1].assets[0].lastModified).isEqualTo("2021-11-17T10:21:24.837+00:00")
                    assertThat(items[2].version).isEqualTo("3")
                    assertThat(items[2].assets).hasSize(1)
                    assertThat(items[2].assets[0].lastModified).isEqualTo("2022-02-09T22:12:14.140+00:00")
                    assertThat(continuationToken).isEqualTo("123abc")
                }
        }
    }

    @Test
    fun `Throw error message with detailed information when connection fails`() {
        server.execute(MockResponse().setResponseCode(500)) {
            val exception = assertThrows<CantusException> {
                nexusRepository
                    .getVersions("no_skatteetaten_aurora", "testname", "testrepo", "123abc")
                    .block()
            }
            assertThat(exception.message).isNotNull()
            assert(exception.message!!.contains("status=500 INTERNAL_SERVER_ERROR"))
            assert(exception.message!!.contains("request_method=\"GET\""))
            assert(exception.message!!.contains("operation=GET_VERSIONS_FROM_NEXUS"))
            assert(exception.message!!.contains("namespace=no_skatteetaten_aurora"))
            assert(exception.message!!.contains("name=testname"))
            assert(exception.message!!.contains("repository=testrepo"))
            assert(exception.message!!.contains("continuationToken=123abc"))
        }
    }

    @Test
    fun `Parse response from the Nexus API getImage`() {

        val response = MockResponse()
            .setJsonFileAsBody("nexusClientSearchResponseGetImage.json")
            .addHeader("Content-Type", "application/json")

        server.execute(response) {
            nexusRepository
                .getImageFromNexus("internal-hosted-client", "no_skatteetaten_aurora_demo/whoami", "2.7.3", "")
                .block()
                .run {
                    assertThat(this).isNotNull()
                    assertThat(this!!.items).isNotNull()
                    assertThat(items).hasSize(1)
                    assertThat(items[0].repository).isEqualTo("internal-hosted-client")
                    assertThat(items[0].name).isEqualTo("no_skatteetaten_aurora_demo/whoami")
                    assertThat(items[0].version).isEqualTo("2.7.3")
                    assertThat(items[0].assets).hasSize(1)
                    assertThat(items[0].assets[0].lastModified).isNullOrEmpty()
                    assertThat(continuationToken).isNullOrEmpty()
                }
        }
    }

    @Test
    fun `Parse empty response from the Nexus API getImage`() {

        val response = MockResponse()
            .setJsonFileAsBody("nexusClientSearchResponseEmpty.json")
            .addHeader("Content-Type", "application/json")

        server.execute(response) {
            nexusRepository
                .getImageFromNexus("internal-hosted-client", "no_skatteetaten_aurora_demo/whoami", "2.7.3", "")
                .block()
                .run {
                    assertThat(this).isNotNull()
                    assertThat(this!!.items).isNotNull()
                    assertThat(items).hasSize(0)
                    assertThat(continuationToken).isNullOrEmpty()
                }
        }
    }

    @Test
    fun `Parse normal response from the Nexus API moveImage`() {

        val response = MockResponse()
            .setJsonFileAsBody("nexusClientMoveResponseSuccessful.json")
            .addHeader("Content-Type", "application/json")

        server.execute(response) {
            nexusRepository.moveImageInNexus(
                "internal-hosted-client",
                "internal-hosted-release",
                "no_skatteetaten_aurora_demo/whoami",
                "2.7.3",
                ""
            )
                .block()
                .run {
                    assertThat(this).isNotNull()
                    assertThat(this!!.status).isEqualTo(200)
                    assertThat(this.message).isEqualTo("Move Successful")
                    assertThat(this.data).isNotNull()
                    assertThat(this.data.destination).isEqualTo("internal-hosted-release")
                    assertThat(this.data.componentsMoved).isNotNull()
                    assertThat(this.data.componentsMoved!!).hasSize(1)
                    assertThat(this.data.componentsMoved!![0].name).isEqualTo("no_skatteetaten_aurora_demo/whoami")
                    assertThat(this.data.componentsMoved!![0].version).isEqualTo("2.7.3")
                    assertThat(this.data.componentsMoved!![0].id).isEqualTo("#27:3065")
                }
        }
    }

    @Test
    fun `Parse not found response from the Nexus API moveImage`() {

        val response = MockResponse()
            .setJsonFileAsBody("nexusClientMoveResponseNoComponents.json")
            .addHeader("Content-Type", "application/json")

        server.execute(response) {
            nexusRepository.moveImageInNexus(
                "internal-hosted-client",
                "internal-hosted-release",
                "no_skatteetaten_aurora_demo/whoami",
                "2.7.3",
                ""
            )
                .block()
                .run {
                    assertThat(this).isNotNull()
                    assertThat(this!!.status).isEqualTo(404)
                    assertThat(this.message).isEqualTo("No components found")
                    assertThat(this.data).isNotNull()
                    assertThat(this.data.destination).isEqualTo("internal-hosted-release")
                }
        }
    }

    @Test
    fun `Response from the moveImage when call to Nexus API is toggled off`() {

        nexusRepositoryMoveInactive.moveImageInNexus(
            "internal-hosted-client",
            "internal-hosted-release",
            "no_skatteetaten_aurora_demo/whoami",
            "2.7.3",
            ""
        )
            .block()
            .run {
                assertThat(this).isNotNull()
                assertThat(this!!.status).isEqualTo(200)
                assertThat(this.message).isEqualTo("Dummy move response")
                assertThat(this.data).isNotNull()
                assertThat(this.data.destination).isEqualTo("internal-hosted-release")
                assertThat(this.data.componentsMoved).isNotNull()
                assertThat(this.data.componentsMoved!!).hasSize(1)
                assertThat(this.data.componentsMoved!![0].name).isEqualTo("no_skatteetaten_aurora_demo/whoami")
                assertThat(this.data.componentsMoved!![0].version).isEqualTo("2.7.3")
                assertThat(this.data.componentsMoved!![0].id).isEqualTo("dummyid")
            }
    }
}
