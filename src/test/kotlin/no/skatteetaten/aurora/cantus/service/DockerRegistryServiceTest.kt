package no.skatteetaten.aurora.cantus.service

import assertk.assert
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.catch
import io.mockk.clearMocks
import no.skatteetaten.aurora.cantus.controller.BadRequestException
import no.skatteetaten.aurora.cantus.controller.SourceSystemException
import no.skatteetaten.aurora.cantus.execute
import no.skatteetaten.aurora.cantus.setJsonFileAsBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.web.reactive.function.client.WebClient

class DockerRegistryServiceTest {

    private val imageGroup = "no_skatteetaten_aurora_demo"
    private val imageName = "whoami"
    private val tagName = "2"

    private val server = MockWebServer()
    private val url = server.url("/")
    private val allowedUrls = listOf("docker-registry.no", "internal-docker-registry.no")
    private val dockerService = DockerRegistryService(WebClient.create(), url.toString(), listOf(url.toString()))

    @BeforeEach
    fun setUp() {
        clearMocks()
    }
/*

    @Test
    fun `Verify fetches manifest information for specified image`() {
        val response =
            MockResponse().setJsonFileAsBody("dockerManifest.json").addHeader("Docker-Content-Digest", "SHA::256")

        server.execute(response) {
            val jsonResponse = dockerService.getImageManifestInformation(imageGroup, imageName, tagName)
            assert(jsonResponse).isNotNull {
                assert(it.actual.dockerDigest).isEqualTo("SHA::256")
                assert(it.actual.dockerVersion).isEqualTo("1.13.1")
                assert(it.actual.timeline["BUILD_DONE"]).isEqualTo("2018-11-05T14:01:22.654389192Z")
            }
        }
    }

    @Test
    fun `Verify fetches all tags for specified image`() {
        val response = MockResponse().setJsonFileAsBody("dockerTagList.json")

        server.execute(response) {
            val jsonResponse = dockerService.getImageTags(imageGroup, imageName)
            assert(jsonResponse).isNotNull {
                assert(it.actual.count).isEqualTo(5)
                assert(it.actual.items[0]).isEqualTo("0")
                assert(it.actual.items[1]).isEqualTo("0.0")
                assert(it.actual.items[2]).isEqualTo("0.0.0")
            }
        }
    }
*/

    @Disabled
    @Test
    fun `Verify groups tags correctly`() {
        val response = MockResponse().setJsonFileAsBody("dockerTagList.json")

        /*server.execute(response) {
            val jsonResponse = dockerService.getImageTagsGroupedBySemanticVersion(imageGroup, imageName)
            assert(jsonResponse).isNotNull {
                assert(it.actual["BUGFIX"]?.size).isEqualTo(2)
                assert(it.actual["MINOR"]?.first()).isEqualTo("0.0")
                assert(it.actual.size).isEqualTo(4)
            }
        }*/
    }

    @ParameterizedTest
    @ValueSource(ints = [500, 400])
    fun `Get image manifest given internal server error in docker registry`(statusCode: Int) {
        server.execute(statusCode) {
            val exception = catch { dockerService.getImageManifestInformation(imageGroup, imageName, tagName) }
            assert(exception).isNotNull {
                assert(it.actual::class).isEqualTo(SourceSystemException::class)
            }
        }
    }

    @Test
    fun `Verify that disallowed docker registry url returns bad request error`() {
        val dockerServiceTestDisallowed = DockerRegistryService(WebClient.create(), url.toString(), allowedUrls)

        server.execute {
            val exception =
                catch { dockerServiceTestDisallowed.getImageManifestInformation(imageGroup, imageName, tagName) }
            assert(exception).isNotNull {
                assert(it.actual::class).isEqualTo(BadRequestException::class)
                assert(it.actual.message).isEqualTo("Invalid Docker Registry URL")
            }
        }
    }
}
