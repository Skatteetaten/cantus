package no.skatteetaten.aurora.cantus.contracts

import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.anyOrNull
import com.nhaarman.mockito_kotlin.given
import no.skatteetaten.aurora.cantus.controller.AuroraResponseAssembler
import no.skatteetaten.aurora.cantus.controller.CantusFailure
import no.skatteetaten.aurora.cantus.controller.ImageTagResource
import no.skatteetaten.aurora.cantus.controller.ImageTagResourceAssembler
import no.skatteetaten.aurora.cantus.controller.Try
import no.skatteetaten.aurora.cantus.service.DockerRegistryService
import no.skatteetaten.aurora.cantus.service.ImageManifestDto
import org.junit.jupiter.api.BeforeEach
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.springframework.boot.test.mock.mockito.MockBean

open class ImagetagresourceBase : ContractBase() {

    @MockBean
    private lateinit var dockerRegistryService: DockerRegistryService

    @MockBean
    private lateinit var imageTagResourceAssembler: ImageTagResourceAssembler

    @BeforeEach
    fun setUp() {
        withContractResponses(this) {
            given(dockerRegistryService.getImageManifestInformation(any())).willReturn(
                ImageManifestDto(
                    dockerVersion = "",
                    dockerDigest = ""
                )
            )
            given(imageTagResourceAssembler.imageTagResourceToAuroraResponse(any())).willReturn(it.response())

        }
    }
}