package no.skatteetaten.aurora.cantus.controller

import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.anyOrNull
import no.skatteetaten.aurora.cantus.service.DockerRegistryService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.BDDMockito
import org.mockito.BDDMockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@ExtendWith(SpringExtension::class)
@WebMvcTest(
    value = [DockerRegistryController::class, ErrorHandler::class],
    properties = ["management.server.port=-1", "cantus.username=username", "cantus.password=password"], secure = false
)
class DockerRegistryControllerTest {
    @MockBean
    private lateinit var dockerService: DockerRegistryService

    @Autowired
    private lateinit var mockMvc: MockMvc

    @ParameterizedTest
    @ValueSource(strings = ["/affiliation/no_skatteetaten_aurora_demo/name/whoami/tag/2/manifest",
        "/affiliation/no_skatteetaten_aurora_demo/name/whoami/tags",
        "/affiliation/no_skatteetaten_aurora_demo/name/whoami/tags/groupBy/semanticVersion"
    ])
    fun `Get docker registry image info`(path : String) {
        given(dockerService.getImageManifest(any(), any(), anyOrNull())).willReturn(mapOf("1" to "2"))
        given(dockerService.getImageTags(any(), anyOrNull())).willReturn(listOf("1", "2"))
        given(dockerService.getImageTagsGroupedBySemanticVersion(any(), anyOrNull())).willReturn(mapOf("1" to listOf("2","3")))
        mockMvc.perform(get(path))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isNotEmpty)
    }

    @ParameterizedTest
    @ValueSource(strings = ["/affiliation/no_skatteetaten_aurora_demo/name/whoami/tag/2/manifest",
        "/affiliation/no_skatteetaten_aurora_demo/name/whoami/tags",
        "/affiliation/no_skatteetaten_aurora_demo/name/whoami/tags/groupBy/semanticVersion"
    ])
    fun `Get docker registry image info given missing resource return 404`(path : String) {
        mockMvc.perform(get(path))
                .andExpect(status().isNotFound)
    }
}