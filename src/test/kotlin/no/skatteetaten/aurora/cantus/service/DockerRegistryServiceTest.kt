package no.skatteetaten.aurora.cantus.service

import assertk.Assert
import assertk.assert
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.catch
import no.skatteetaten.aurora.cantus.ApplicationConfig
import no.skatteetaten.aurora.cantus.controller.BadRequestException
import no.skatteetaten.aurora.cantus.controller.CantusException
import no.skatteetaten.aurora.cantus.controller.ImageRepoCommand
import no.skatteetaten.aurora.cantus.controller.SourceSystemException
import no.skatteetaten.aurora.cantus.execute
import no.skatteetaten.aurora.cantus.setJsonFileAsBody
import okhttp3.Headers
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient

class DockerRegistryServiceTest {
    private val server = MockWebServer()
    private val url = server.url("/")

    private val imageRepoCommand = ImageRepoCommand(
        registry = "${url.host()}:${url.port()}",
        imageGroup = "no_skatteetaten_aurora_demo",
        imageName = "whoami",
        imageTag = "2",
        bearerToken = "bearer token"
    )

    private val applicationConfig = ApplicationConfig()

    private val dockerService = DockerRegistryService(
        applicationConfig.webClient(WebClient.builder(), applicationConfig.tcpClient(100, 100, 100)),
        RegistryMetadataResolver(listOf(imageRepoCommand.registry)),
        ImageRegistryUrlBuilder()
    )

    @Test
    fun `Verify fetches manifest information for specified image`() {
        val response =
            MockResponse().setJsonFileAsBody("dockerManifestV1.json").addHeader("Docker-Content-Digest", "SHA::256")

        server.execute(response) {
            val jsonResponse = dockerService.getImageManifestInformation(imageRepoCommand)
            assert(jsonResponse).isNotNull {
                assert(it.actual.dockerDigest).isEqualTo("SHA::256")
                assert(it.actual.dockerVersion).isEqualTo("1.13.1")
                assert(it.actual.buildEnded).isEqualTo("2018-11-05T14:01:22.654389192Z")
            }
        }
    }

    @Test
    fun `Verify fetches all tags for specified image`() {
        val response = MockResponse().setJsonFileAsBody("dockerTagList.json")

        server.execute(response) {
            val jsonResponse: ImageTagsWithTypeDto = dockerService.getImageTags(imageRepoCommand)
            assert(jsonResponse).isNotNull {
                assert(it.actual.tags.size).isEqualTo(5)
                assert(it.actual.tags[0].name).isEqualTo("0")
                assert(it.actual.tags[1].name).isEqualTo("0.0")
                assert(it.actual.tags[2].name).isEqualTo("0.0.0")
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [500, 400, 404, 403, 501, 401, 418])
    fun `Get image manifest given internal server error in docker registry`(statusCode: Int) {
        val headers = Headers.of(
            mapOf(
                HttpHeaders.CONTENT_TYPE to MediaType.APPLICATION_JSON_VALUE,
                dockerService.dockerContentDigestLabel to "sha256"
            )
        )
        server.execute(status = statusCode, headers = headers) {
            val exception = catch { dockerService.getImageManifestInformation(imageRepoCommand) }
            assert(exception).isNotNull {
                println(it.actual.message)
                assert(it.actual::class).isEqualTo(SourceSystemException::class)
            }
        }
    }

    @Test
    fun `Verify that empty tag list throws SourceSystemException`() {
        server.execute(ImageTagsResponseDto(emptyList())) {
            val exception = catch { dockerService.getImageTags(imageRepoCommand) }

            assert(exception).isNotNull {
                assert(it.actual::class).isEqualTo(SourceSystemException::class)
                assert(it.actual.message).isEqualTo("Tags not found for image ${imageRepoCommand.defaultRepo}")
            }
        }
    }

    @Test
    fun `Verify that empty manifest response throws SourceSystemException`() {
        val response = MockResponse().addHeader(dockerService.dockerContentDigestLabel, "sha::256")

        server.execute(response) {
            val exception = catch { dockerService.getImageManifestInformation(imageRepoCommand) }

            println(exception)
            assert(exception).isNotNull {
                assert(it.actual::class).isEqualTo(SourceSystemException::class)
            }
        }
    }

    @Test
    fun `Verify that empty body throws SourceSystemException`() {
        val response = MockResponse()
            .setJsonFileAsBody("emptyDockerManifestV1.json")
            .addHeader("Docker-Content-Digest", "SHA::256")

        server.execute(response) {
            val exception = catch { dockerService.getImageManifestInformation(imageRepoCommand) }

            assert(exception).isNotNull {
                assert(it.actual::class).isEqualTo(SourceSystemException::class)
            }
        }
    }

    @Test
    fun `Verify that non existing Docker-Content-Digest throws SourceSystemException`() {
        val response = MockResponse()
            .setJsonFileAsBody("dockerManifestV1.json")

        server.execute(response) {
            val exception = catch { dockerService.getImageManifestInformation(imageRepoCommand) }

            assert(exception).isNotNull {
                assert(it.actual::class).isEqualTo(SourceSystemException::class)
                assert(it.actual).beginsWith("Response did not contain")
            }
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = SocketPolicy::class,
        names = ["DISCONNECT_AFTER_REQUEST", "DISCONNECT_DURING_RESPONSE_BODY", "NO_RESPONSE"],
        mode = EnumSource.Mode.INCLUDE
    )
    fun `Handle connection failure in retrieve that throws exception`(socketPolicy: SocketPolicy) {

        val response = MockResponse()
            .setJsonFileAsBody("dockerTagList.json")
            .apply { this.socketPolicy = socketPolicy }

        server.execute(response) {
            val exception = catch { dockerService.getImageTags(imageRepoCommand) }
            assert(exception).isNotNull {
                println(exception)
                assert(it.actual::class).isEqualTo(CantusException::class)
            }
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = SocketPolicy::class,
        names = ["DISCONNECT_AFTER_REQUEST", "DISCONNECT_DURING_RESPONSE_BODY", "NO_RESPONSE"],
        mode = EnumSource.Mode.INCLUDE
    )
    fun `Handle connection failure in exchange that throws exception`(socketPolicy: SocketPolicy) {

        val response = MockResponse()
            .setJsonFileAsBody("dockerManifestV1.json")
            .addHeader(dockerService.dockerContentDigestLabel, "SHA::256")
            .apply { this.socketPolicy = socketPolicy }

        server.execute(response) {
            val exception = catch { dockerService.getImageManifestInformation(imageRepoCommand) }
            assert(exception).isNotNull {
                println(exception)
                assert(it.actual::class).isEqualTo(CantusException::class)
            }
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = SocketPolicy::class,
        names = ["KEEP_OPEN", "DISCONNECT_AT_END", "UPGRADE_TO_SSL_AT_END"],
        mode = EnumSource.Mode.INCLUDE
    )
    fun `Handle connection failure that returns response`(socketPolicy: SocketPolicy) {

        val response = MockResponse()
            .setJsonFileAsBody("dockerTagList.json")
            .apply { this.socketPolicy = socketPolicy }

        server.execute(response) {
            val result = dockerService.getImageTags(imageRepoCommand)
            assert(result).isNotNull()
        }
    }

    @Test
    fun `Verify that missing authorization token returns bad request given authentication mode set to kubernetes token`() {

        val dockerServiceNoBearer = DockerRegistryService(
            WebClient.create(),
            RegistryMetadataResolver(listOf("noBearerToken.com")),
            ImageRegistryUrlBuilder()
        )

        val imageRepoCommandNoToken =
            ImageRepoCommand("noBearerToken.com", "no_skatteetaten_aurora_demo", "whoami", "2")

        server.execute {
            val exception = catch { dockerServiceNoBearer.getImageTags(imageRepoCommandNoToken) }
            assert(exception).isNotNull {
                assert(it.actual::class).isEqualTo(BadRequestException::class)
                assert(it.actual.message).isEqualTo("Authorization bearer token is not present")
            }
        }

        server.execute {
            val exception = catch { dockerServiceNoBearer.getImageManifestInformation(imageRepoCommandNoToken) }
            assert(exception).isNotNull {
                assert(it.actual::class).isEqualTo(BadRequestException::class)
                assert(it.actual.message).isEqualTo("Authorization bearer token is not present")
            }
        }
    }

    @Test
    fun `Verify that if V2 content type is set then retrieve manifest with V2 method`() {
        val response = MockResponse()
            .setJsonFileAsBody("dockerManifestV2.json")
            .setHeader("Content-Type", "application/vnd.docker.distribution.manifest.v2+json")
            .addHeader("Docker-Content-Digest", "sha256")

        val response2 = MockResponse()
            .setJsonFileAsBody("dockerManifestV2Config.json")

        val requests = server.execute(response, response2) {

            val jsonResponse = dockerService.getImageManifestInformation(imageRepoCommand)

            assert(jsonResponse).isNotNull {
                assert(it.actual.dockerDigest).isEqualTo("sha256")
                assert(it.actual.nodeVersion).isEqualTo(null)
                assert(it.actual.buildEnded).isEqualTo("2018-11-05T14:01:22.654389192Z")
                assert(it.actual.java?.major).isEqualTo("8")
            }
        }
        assert(requests.size).isEqualTo(2)
    }

    @Test
    fun `Verify that V2 manifest not found is handled`() {
        val response = MockResponse()
            .setJsonFileAsBody("dockerManifestV2.json")
            .setHeader("Content-Type", "application/vnd.docker.distribution.manifest.v2+json")
            .addHeader("Docker-Content-Digest", "sha256")
        val response2 = MockResponse()

        val requests = server.execute(response, response2) {
            val exception = catch { dockerService.getImageManifestInformation(imageRepoCommand) }

            assert(exception).isNotNull {
                assert(it.actual::class).isEqualTo(SourceSystemException::class)
                assert(it.actual).beginsWith("Unable to retrieve Vl1 manifest")
            }
        }
    }

    private fun Assert<Throwable>.beginsWith(subString: String) = actual.message?.startsWith(subString)
}
