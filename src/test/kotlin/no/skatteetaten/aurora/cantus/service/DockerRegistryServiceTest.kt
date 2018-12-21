package no.skatteetaten.aurora.cantus.service

import io.mockk.clearMocks
import io.mockk.every
import okhttp3.mockwebserver.MockWebServer
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DockerRegistryServiceTest {

    private val imageRepoName = "no_skatteetaten_aurora/boober"
    private val tagName = "1"

    private val server = MockWebServer()
    private val url = server.url("/")

    @BeforeEach
    fun setUp() {
        clearMocks()

        every {

        }
    }

    @Test
    fun 'Verify fetches all tags for specified image' () {
        val request = server.execute(tagslistResponse)
    }
}

@Language("JSON")
private const val tagsListResponse = """{
    "name": "no_skatteetaten_aurora/boober",
    "tags": [
       "master-SNAPSHOT",
        "1.0.0-rc.1-b2.2.3-oracle8-1.4.0",
    "1.0.0-rc.2-b2.2.3-oracle8-1.4.0",
    "develop-SNAPSHOT",
    "1"
    ]


}"""