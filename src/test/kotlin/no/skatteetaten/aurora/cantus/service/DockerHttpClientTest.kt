package no.skatteetaten.aurora.cantus.service

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import assertk.assertions.message
import assertk.catch
import no.skatteetaten.aurora.cantus.ApplicationConfig
import no.skatteetaten.aurora.cantus.AuroraIntegration.AuthType.Bearer
import no.skatteetaten.aurora.cantus.controller.ImageRepoCommand
import no.skatteetaten.aurora.cantus.controller.SourceSystemException
import no.skatteetaten.aurora.cantus.createObjectMapper
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.execute
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.setJsonFileAsBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient

class DockerHttpClientTest {
    private val server = MockWebServer()
    private val url = server.url("/")

    private val imageRepoCommand = ImageRepoCommand(
        registry = "${url.host()}:${url.port()}",
        imageGroup = "no_skatteetaten_aurora_demo",
        imageName = "whoami",
        imageTag = "2",
        token = "bearer token",
        authType = Bearer,
        url = "http://${url.host()}:${url.port()}/v2"
    )

    private val applicationConfig = ApplicationConfig()

    private val httpClient = DockerHttpClient(
        applicationConfig.webClient(
            WebClient.builder(),
            applicationConfig.tcpClient(100, 100, 100, null),
            "cantus",
            "123"
        )
    )

    @Test
    fun `test put manifest failes`() {
        val response =
            "{\"errors\":[{\"code\":\"BLOB_UNKNOWN\",\"message\":\"blob unknown to registry\",\"detail\":\"sha256:303510ed0dee065d6dc0dd4fbb1833aa27ff6177e7dfc72881ea4ea0716c82a1\"}]}"

        val manifest = ImageManifestResponseDto(manifestV2, "abc", createObjectMapper().readTree("{}"))

        server.execute(MockResponse().setResponseCode(404).setBody(response)) {
            val exception = catch { httpClient.putManifest(imageRepoCommand, manifest) }
            assertThat(exception)
                .isNotNull().isInstanceOf(SourceSystemException::class)
                .message().isNotNull()
                .contains("blob unknown to registry")
        }
    }

    @Test
    fun `test digest authentication failed`() {
        server.execute(MockResponse().setResponseCode(401).setBody("Unauthorized")) {
            val exception = catch { httpClient.digestExistInRepo(imageRepoCommand, "abc") }
            assertThat(exception)
                .isNotNull().isInstanceOf(SourceSystemException::class)
                .message().isNotNull()
                .contains("Error when checking if blob=abc exist in repository=no_skatteetaten_aurora_demo/whoami code=401")
        }
    }

    @Test
    fun `test digest does not exist in repo`() {
        server.execute(MockResponse().setResponseCode(404)) {
            val result = httpClient.digestExistInRepo(imageRepoCommand, "abc")
            assertThat(result).isFalse()
        }
    }

    @Test
    fun `test digest exist in repo`() {
        server.execute(MockResponse()) {
            val result = httpClient.digestExistInRepo(imageRepoCommand, "abc")
            assertThat(result).isTrue()
        }
    }

    @Test
    fun `Verify fetches manifest information for specified image`() {
        val response =
            MockResponse()
                .setJsonFileAsBody("dockerManifestV1.json")
                .addHeader("Docker-Content-Digest", "SHA::256")
                .setHeader("Content-Type", MediaType.valueOf(manifestV1))

        server.execute(response) {
            val jsonResponse = httpClient.getImageManifest(imageRepoCommand)
            assertThat(jsonResponse).isNotNull().given {
                assertThat(it.contentType).contains("v1")
                assertThat(it.dockerContentDigest).isEqualTo("SHA::256")
                assertThat(it.manifestBody).isNotNull()
            }
        }
    }

    @Test
    fun `Verify fetches all tags for specified image`() {
        val response = MockResponse().setJsonFileAsBody("dockerTagList.json")

        server.execute(response) {
            val jsonResponse: ImageTagsResponseDto? = httpClient.getImageTags(imageRepoCommand)
            assertThat(jsonResponse).isNotNull().given {
                assertThat(it.tags.size).isEqualTo(5)
                assertThat(it.tags[0]).isEqualTo("0")
                assertThat(it.tags[1]).isEqualTo("0.0")
                assertThat(it.tags[2]).isEqualTo("0.0.0")
            }
        }
    }

    @Test
    fun `Verify that empty manifest response throws SourceSystemException`() {
        val response = MockResponse().addHeader(httpClient.dockerContentDigestLabel, "sha::256")

        server.execute(response) {
            val exception = catch { httpClient.getImageManifest(imageRepoCommand) }

            assertThat(exception).isNotNull().isInstanceOf(SourceSystemException::class)
        }
    }

    @Test
    fun `Verify that non existing Docker-Content-Digest throws SourceSystemException`() {
        val response = MockResponse()
            .setJsonFileAsBody("dockerManifestV1.json")

        server.execute(response) {
            val exception = catch { httpClient.getImageManifest(imageRepoCommand) }

            assertThat(exception)
                .isNotNull().isInstanceOf(SourceSystemException::class)
                .message().isNotNull().contains("Response did not contain")
        }
    }

    @Test
    fun `Verify that if V2 content type is set then retrieve manifest with V2 method`() {
        val response = MockResponse()
            .setJsonFileAsBody("dockerManifestV2.json")
            .setHeader("Content-Type", "application/vnd.docker.distribution.manifest.v2+json")
            .addHeader("Docker-Content-Digest", "sha256")

        server.execute(response) {

            val dto = httpClient.getImageManifest(imageRepoCommand)

            assertThat(dto).isNotNull().given {
                assertThat(it.contentType).contains("v2")
                assertThat(it.dockerContentDigest).isEqualTo("sha256")
                assertThat(it.manifestBody).isNotNull()
            }
        }
    }
}