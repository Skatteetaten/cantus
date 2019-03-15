package no.skatteetaten.aurora.cantus.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.times
import com.nhaarman.mockito_kotlin.verify
import kotlinx.coroutines.newFixedThreadPoolContext
import no.skatteetaten.aurora.cantus.ImageManifestDtoBuilder
import no.skatteetaten.aurora.cantus.ImageTagsWithTypeDtoBuilder
import no.skatteetaten.aurora.cantus.service.DockerRegistryService
import no.skatteetaten.aurora.cantus.service.ImageTagTypedDto
import no.skatteetaten.aurora.cantus.service.ImageTagsWithTypeDto
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

private const val defaultTestRegistry: String = "docker.com"

@WebMvcTest(
    value = [
        DockerRegistryController::class,
        ImageTagResourceAssembler::class,
        AuroraResponseAssembler::class,
        ImageRepoCommandAssembler::class
    ],
    secure = false
)
class DockerRegistryControllerTest {

    @TestConfiguration
    class DockerRegistryControllerTestConfiguration {

        @Bean
        fun threadPoolContext(@Value("\${cantus.threadPoolSize:6}") threadPoolSize: Int) =
            newFixedThreadPoolContext(threadPoolSize, "cantus")
    }

    @MockBean
    private lateinit var dockerService: DockerRegistryService

    @Autowired
    private lateinit var mockMvc: MockMvc

    private val tags = ImageTagsWithTypeDtoBuilder("no_skatteetaten_aurora_demo", "whoami").build()

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/tags?repoUrl=$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami",
            "/tags/semantic?repoUrl=$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami"
        ]
    )
    fun `Get docker registry image tags with GET`(path: String) {
        val tags = ImageTagsWithTypeDto(tags = listOf(ImageTagTypedDto("test")))

        given(dockerService.getImageTags(any())).willReturn(tags)

        given(
            dockerService.getImageTags(any())
        ).willReturn(tags)

        mockMvc.perform(get(path))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isNotEmpty)
    }

    @Test
    fun `Get docker registry image manifest with POST`() {
        val manifest = ImageManifestDtoBuilder().build()
        val tagUrl = listOf("$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami/2")

        given(dockerService.getImageManifestInformation(any())).willReturn(manifest)

        mockMvc.perform(
            post("/manifest").setBody(tagUrl))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isNotEmpty)
            .andExpect(jsonPath("$.success").value(true))
    }

    @Test
    fun `Get docker registry image tags with GET given missing resource`() {
        val path = "/tags?repoUrl=$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami"
        val notFoundStatus = HttpStatus.NOT_FOUND
        val repoUrl = path.split("=")[1]

        given(dockerService.getImageTags(any())).willThrow(
            SourceSystemException(
                message = "Resource could not be found status=${notFoundStatus.value()} message=${notFoundStatus.reasonPhrase}",
                sourceSystem = "https://docker.com"
            )
        )

        mockMvc.perform(get(path))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isEmpty)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.failure[0].url").value(repoUrl))
            .andExpect(jsonPath("$.failure[0].errorMessage").value("Resource could not be found status=${notFoundStatus.value()} message=${notFoundStatus.reasonPhrase}"))
    }

    @Test
    fun `Get docker registry image manifest with POST given missing resource`() {
        val tagUrl = listOf("$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami/2")

        val notFoundStatus = HttpStatus.NOT_FOUND

        given(dockerService.getImageManifestInformation(any())).willThrow(
            SourceSystemException(
                message = "Resource could not be found status=${notFoundStatus.value()} message=${notFoundStatus.reasonPhrase}",
                sourceSystem = "https://docker.com"
            )
        )

        mockMvc.perform(post("/manifest").setBody(tagUrl))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isEmpty)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.failure[0].url").value(tagUrl.first()))
            .andExpect(jsonPath("$.failure[0].errorMessage").value("Resource could not be found status=${notFoundStatus.value()} message=${notFoundStatus.reasonPhrase}"))
    }

    @Test
    fun `Get imageManifestList given multiple tagUrls return AuroraResponse`() {
        val manifest = ImageManifestDtoBuilder().build()

        given(dockerService.getImageManifestInformation(any())).willReturn(manifest)

        val tagUrls = listOf(
            "$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami/2",
            "$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami/1"
        )

        mockMvc.perform(
            post("/manifest").setBody(tagUrls))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.count").value(2))

        verify(dockerService, times(2)).getImageManifestInformation(any())
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/tags?repoUrl=no_skatteetaten_aurora_demo/whaomi",
            "/tags/semantic?repoUrl=$defaultTestRegistry/no_skatteetaten_aurora"
        ]
    )
    fun `Get request given invalid repoUrl throw BadRequestException`(path: String) {

        val repoUrl = path.split("=")[1]

        mockMvc.perform(get(path))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isEmpty)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.failure[0].url").value(repoUrl))
            .andExpect(jsonPath("$.failure[0].errorMessage").value("Invalid url=$repoUrl"))
    }

    @Test
    fun `Post request given invalid tagUrl in body`() {
        val tagUrl = listOf("$defaultTestRegistry/namespace/test-application")

        given(dockerService.getImageManifestInformation(any()))
            .willThrow(BadRequestException("Invalid url=${tagUrl.first()}"))

        mockMvc.perform(
            post("/manifest").setBody(tagUrl))
            .andExpect { jsonPath("$.items").isEmpty }
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.failure[0].url").value(tagUrl.first()))
            .andExpect(jsonPath("$.failure[0].errorMessage").value("Invalid url=${tagUrl.first()}"))
    }

    @Test
    fun `Get tags given no authorization token throw ForbiddenException`() {
        val path = "/tags?repoUrl=$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami"
        given(dockerService.getImageTags(any()))
            .willThrow(ForbiddenException("Authorization bearer token is not present"))

        mockMvc.perform(get(path))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.failure[0].errorMessage").value("Authorization bearer token is not present"))
            .andExpect(jsonPath("$.items").isEmpty)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `Get manifest given no authorization token throw ForbiddenException`() {
        val tagUrl = listOf("$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami/2")

        given(dockerService.getImageManifestInformation(any()))
            .willThrow(ForbiddenException("Authorization bearer token is not present"))

        mockMvc.perform(post("/manifest").setBody(tagUrl))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.failure[0].errorMessage").value("Authorization bearer token is not present"))
            .andExpect(jsonPath("$.items").isEmpty)
            .andExpect(jsonPath("$.success").value(false))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/tags?repoUrl=$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami",
            "/tags/semantic?repoUrl=$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami"
        ]
    )
    fun `Get request given throw IllegalStateException`(path: String) {
        given(dockerService.getImageTags(any()))
            .willThrow(IllegalStateException("An error has occurred"))

        mockMvc.perform(get(path))
            .andDo { print(it.response.contentAsString) }
            .andExpect(jsonPath("$.failure[0].errorMessage").value("An error has occurred"))
            .andExpect(jsonPath("$.items").isEmpty)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `Post request given throw IllegalStateException`() {
        val tagUrl = listOf("$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami/2")

        given(dockerService.getImageManifestInformation(any()))
            .willThrow(IllegalStateException("An error has occurred"))

        mockMvc.perform(
            post("/manifest").setBody(tagUrl))
            .andDo { print(it.response.contentAsString) }
            .andExpect(jsonPath("$.failure[0].errorMessage").value("An error has occurred"))
            .andExpect(jsonPath("$.items").isEmpty)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `Verify groups tags correctly`() {
        val path = "/tags/semantic?repoUrl=$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami"

        given(
            dockerService.getImageTags(any())
        ).willReturn(tags)

        mockMvc.perform(get(path))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.count").value(3))
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.items[0].group").value("MAJOR"))
            .andExpect(jsonPath("$.items[0].tagResource[0].name").value("0"))
            .andExpect(jsonPath("$.items[0].itemsInGroup").value(1))
            .andExpect(jsonPath("$.items[2].group").value("BUGFIX"))
            .andExpect(jsonPath("$.items[2].tagResource[0].name").value("0.0.0"))
            .andExpect(jsonPath("$.items[2].itemsInGroup").value(1))
    }

    @Test
    fun `Verify that allowed override docker registry url is validated as allowed`() {
        val path = "/tags?repoUrl=allowedurl.no/no_skatteetaten_aurora_demo/whoami"

        given(dockerService.getImageTags(any()))
            .willReturn(tags)

        mockMvc.perform(get(path))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
    }

    @Test
    fun `Verify that disallowed docker registry url returns bad request error`() {
        val repoUrl = "vg.no/no_skatteetaten_aurora_demo/whoami"
        val path = "/tags?repoUrl=$repoUrl"

        given(
            dockerService.getImageTags(any())
        ).willReturn(tags)

        mockMvc.perform(get(path))
            .andExpect(jsonPath("$.failure[0].errorMessage").value("Invalid Docker Registry URL url=vg.no"))
            .andExpect(jsonPath("$.failure[0].url").value(repoUrl))
            .andExpect(jsonPath("$.success").value(false))
    }
}

private fun MockHttpServletRequestBuilder.setBody(tagUrls: List<String>): RequestBuilder =
    this.contentType(MediaType.APPLICATION_JSON_UTF8_VALUE)
        .content(jacksonObjectMapper().writeValueAsString(tagUrls))

