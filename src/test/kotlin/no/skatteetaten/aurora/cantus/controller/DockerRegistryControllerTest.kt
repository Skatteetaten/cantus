package no.skatteetaten.aurora.cantus.controller

import no.skatteetaten.aurora.cantus.service.DockerRegistryService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers

@ExtendWith(SpringExtension::class)
@WebMvcTest(
    value = [DockerRegistryController::class, ErrorHandler::class],
    properties = ["management.server.port=-1", "cantus.username=username", "cantus.password=password"]
)
class DockerRegistryControllerTest {
    @MockBean
    private lateinit var dockerService: DockerRegistryService

    @MockBean
    private lateinit var passwordEncoder: PasswordEncoder

    @MockBean
    private lateinit var baseAuthenticationEntryPoint: BasicAuthenticationEntryPoint

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `Get manifest information`() {
        mockMvc.perform(MockMvcRequestBuilders.get("/affiliation/no_skatteetaten_aurora_demo/name/whoami/tag/2/manifest"))
            .andExpect(MockMvcResultMatchers.status().isOk)
    }
}