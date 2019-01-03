package no.skatteetaten.aurora.cantus.controller

import no.skatteetaten.aurora.cantus.service.DockerRegistryService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers

@ExtendWith(SpringExtension::class)
@WebMvcTest
class DockerRegistryControllerTest {
    @MockBean
    private lateinit var dockerService : DockerRegistryService

    @Autowired
    private lateinit var mockMvc : MockMvc

    @Test
    fun `Get manifest information`() {
        mockMvc.perform(MockMvcRequestBuilders.get("/affiliation/no_skatteetaten_aurora_demo/name/whoami/tag/2/manifest"))
                .andExpect(MockMvcResultMatchers.status().isOk)
    }

}