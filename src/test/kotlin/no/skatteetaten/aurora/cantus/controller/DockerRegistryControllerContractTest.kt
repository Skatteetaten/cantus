package no.skatteetaten.aurora.cantus.controller

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.newFixedThreadPoolContext
import no.skatteetaten.aurora.cantus.AuroraIntegration
import no.skatteetaten.aurora.cantus.ImageManifestDtoBuilder
import no.skatteetaten.aurora.cantus.ImageTagsWithTypeDtoBuilder
import no.skatteetaten.aurora.cantus.createObjectMapper
import no.skatteetaten.aurora.cantus.service.DockerRegistryService
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.TestObjectMapperConfigurer
import no.skatteetaten.aurora.mockmvc.extensions.contentTypeJson
import no.skatteetaten.aurora.mockmvc.extensions.post
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.MockMvc

private const val defaultTestRegistry: String = "docker.com"

@AutoConfigureRestDocs
@WebMvcTest(
    value = [
        DockerRegistryController::class,
        AuroraResponseAssembler::class,
        ImageTagResourceAssembler::class,
        ImageRepoCommandAssembler::class,
        AuroraIntegration::class,
        ImageBuildTimeline::class
    ]
)
class DockerRegistryControllerContractTest {

    @TestConfiguration
    @EnableConfigurationProperties(AuroraIntegration::class)
    class DockerRegistryControllerContractTestConfiguration {
        @Bean
        fun threadPoolContext() = newFixedThreadPoolContext(2, "cantus")

        @Bean
        fun dockerService() = mockk<DockerRegistryService>()
    }

    @Autowired
    private lateinit var dockerService: DockerRegistryService

    @Autowired
    private lateinit var mockMvc: MockMvc

    private val tags = ImageTagsWithTypeDtoBuilder("no_skatteetaten_aurora_demo", "whoami").build()

    init {
        TestObjectMapperConfigurer.objectMapper = createObjectMapper()
    }

    @AfterAll
    fun tearDown() {
        TestObjectMapperConfigurer.reset()
    }

    @Test
    fun `Get docker registry image manifest with POST`() {
        val manifest = ImageManifestDtoBuilder().build()
        val tagUrlsWrapper = TagUrlsWrapper(
            listOf(
                "$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami/2",
                "$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami/1"
            )
        )

        every {
            dockerService.getImageManifestInformation(any())
        } answers {
            val imageRepoCommand = firstArg<ImageRepoCommand>()
            if (imageRepoCommand.imageTag == "1") throw SourceSystemException("Docker api not responding")
            else manifest
        }

        mockMvc.post(
            Path("/manifest"),
            headers = HttpHeaders().contentTypeJson(),
            body = createObjectMapper().writeValueAsString(tagUrlsWrapper)
        ) {
            statusIsOk()
                .responseJsonPath()
            // .responseJsonPath("$.success").equalsValue(false)
            // .responseJsonPath("$.items").isNotEmpty()
            // .responseJsonPath("$.failureCount").equalsValue(1)
            // .responseJsonPath("$.successCount").equalsValue(1)
        }

        // verify(dockerService, times(2)).getImageManifestInformation(any())
    }

    @Test
    fun `Get docker registry image tags with GET`() {
        val path = "/tags?repoUrl=url/namespace/name"
/*

        given(dockerService.getImageTags(any(), any())).willReturn(tags)
*/

/*
        val tagResource = given(mockedImageTagResourceAssembler.tagResourceToAuroraResponse(any()))
            .withContractResponse("tagresource/TagResource") {
                willReturn(content)
            }.mockResponse

        mockMvc.get(Path(path)) {
            statusIsOk()
                .responseJsonPath("$").equalsObject(tagResource)
        }
*/
    }

    @Test
    fun `Get docker registry image tags with GET given missing resource`() {
        val path = "/tags?repoUrl=url/namespace/missing"
        val notFoundStatus = HttpStatus.NOT_FOUND

/*
        given(dockerService.getImageTags(any(), any())).willThrow(
            SourceSystemException(
                message = "Resource could not be found status=${notFoundStatus.value()} message=${notFoundStatus.reasonPhrase}",
                sourceSystem = "https://docker.com"
            )
        )

        val tagResourceNotFound = given(mockedImageTagResourceAssembler.tagResourceToAuroraResponse(any()))
            .withContractResponse("tagresource/TagResourceNotFound") {
                willReturn(content)
            }.mockResponse

        mockMvc.get(Path(path)) {
            statusIsOk()
                .responseJsonPath("$").equalsObject(tagResourceNotFound)
        }
*/
    }
}
