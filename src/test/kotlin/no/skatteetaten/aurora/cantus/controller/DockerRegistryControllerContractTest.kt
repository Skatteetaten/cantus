package no.skatteetaten.aurora.cantus.controller

import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.times
import com.nhaarman.mockito_kotlin.verify
import kotlinx.coroutines.newFixedThreadPoolContext
import no.skatteetaten.aurora.cantus.ImageManifestDtoBuilder
import no.skatteetaten.aurora.cantus.ImageTagsWithTypeDtoBuilder
import no.skatteetaten.aurora.cantus.createObjectMapper
import no.skatteetaten.aurora.cantus.service.DockerRegistryService
import no.skatteetaten.aurora.cantus.service.ImageTagTypedDto
import no.skatteetaten.aurora.cantus.service.ImageTagsWithTypeDto
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.contentType
import no.skatteetaten.aurora.mockmvc.extensions.mock.withContractResponse
import no.skatteetaten.aurora.mockmvc.extensions.post
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.BDDMockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers

private const val defaultTestRegistry: String = "docker.com"

@AutoConfigureRestDocs
@WebMvcTest(
    value = [
        DockerRegistryController::class,
        AuroraResponseAssembler::class,
        ImageTagResourceAssembler::class,
        ImageRepoCommandAssembler::class
    ],
    secure = false
)
class DockerRegistryControllerContractTest {

    @TestConfiguration
    class DockerRegistryControllerContractTestConfiguration {

        @Bean
        fun threadPoolContext(@Value("\${cantus.threadPoolSize:6}") threadPoolSize: Int) =
            newFixedThreadPoolContext(threadPoolSize, "cantus")
    }

    @MockBean
    private lateinit var dockerService: DockerRegistryService

    private val testObjectMapper = createObjectMapper()

    @Autowired
    private lateinit var mockMvc: MockMvc

    private val tags = ImageTagsWithTypeDtoBuilder("no_skatteetaten_aurora_demo", "whoami").build()

    @MockBean
    private lateinit var mockedImageTagResourceAssembler: ImageTagResourceAssembler

    @Test
    fun `Get docker registry image manifest with POST`() {
        val manifest = ImageManifestDtoBuilder().build()
        val tagUrl = listOf(
            "$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami/2",
            "$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami/1"
        )

        BDDMockito.given(dockerService.getImageManifestInformation(any())).willReturn(manifest)

        val imageTagResource =
            BDDMockito.given(mockedImageTagResourceAssembler.imageTagResourceToAuroraResponse(any()))
                .withContractResponse("imagetagresource/partialSuccess", objectMapper = testObjectMapper)
                { willReturn(content) }.mockResponse

        mockMvc.post(
            path = Path("/manifest"),
            headers = HttpHeaders().contentType(),
            body = tagUrl,
            docsIdentifier = "getDockerRegistryImageManifest"
        ) {
            statusIsOk()
                .responseJsonPath("$").equalsObject(imageTagResource, objectMapper = testObjectMapper)
                .responseJsonPath("$.success").equalsValue(false)
        }

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

        mockMvc.perform(MockMvcRequestBuilders.get(path))
            .andExpect(MockMvcResultMatchers.status().isOk)
            .andExpect(MockMvcResultMatchers.jsonPath("$.items").isEmpty)
            .andExpect(MockMvcResultMatchers.jsonPath("$.success").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.failure[0].url").value(repoUrl))
            .andExpect(MockMvcResultMatchers.jsonPath("$.failure[0].errorMessage").value("Invalid url=$repoUrl"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/tags?repoUrl=$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami",
            "/tags/semantic?repoUrl=$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami"
        ]
    )
    fun `Get docker registry image tags with GET`(path: String) {
        val tags = ImageTagsWithTypeDto(tags = listOf(ImageTagTypedDto("test")))

        BDDMockito.given(dockerService.getImageTags(any())).willReturn(tags)

        mockMvc.perform(MockMvcRequestBuilders.get(path))
            .andExpect(MockMvcResultMatchers.status().isOk)
            .andExpect(MockMvcResultMatchers.jsonPath("$").isNotEmpty)
    }

    @Test
    fun `Get docker registry image tags with GET given missing resource`() {
        val path = "/tags?repoUrl=$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami"
        val notFoundStatus = HttpStatus.NOT_FOUND
        val repoUrl = path.split("=")[1]

        BDDMockito.given(dockerService.getImageTags(any())).willThrow(
            SourceSystemException(
                message = "Resource could not be found status=${notFoundStatus.value()} message=${notFoundStatus.reasonPhrase}",
                sourceSystem = "https://docker.com"
            )
        )

        mockMvc.perform(MockMvcRequestBuilders.get(path))
            .andExpect(MockMvcResultMatchers.status().isOk)
            .andExpect(MockMvcResultMatchers.jsonPath("$.items").isEmpty)
            .andExpect(MockMvcResultMatchers.jsonPath("$.success").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.failure[0].url").value(repoUrl))
            .andExpect(MockMvcResultMatchers.jsonPath("$.failure[0].errorMessage").value("Resource could not be found status=${notFoundStatus.value()} message=${notFoundStatus.reasonPhrase}"))
    }
}