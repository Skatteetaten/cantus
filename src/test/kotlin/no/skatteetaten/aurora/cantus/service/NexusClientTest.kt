package no.skatteetaten.aurora.cantus.service

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import no.skatteetaten.aurora.cantus.controller.CantusException
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.execute
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.setJsonFileAsBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.reactive.function.client.WebClient

class NexusClientTest {

    private val server = MockWebServer()
    private val nexusClient = NexusClient(WebClient.builder(), server.url("/").toString())

    @Test
    fun `Parse response from the Nexus API`() {

        val response = MockResponse()
            .setJsonFileAsBody("nexusClientResponse.json")
            .addHeader("Content-Type", "application/json")

        server.execute(response) {
            nexusClient
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
                nexusClient
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
}