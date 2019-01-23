package no.skatteetaten.aurora.cantus.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

enum class AuthenticationMethod { NONE, KUBERNETES_TOKEN }

data class RegistryMetadata(
    val registry: String,
    val apiSchema: String,
    val authenticationMethod: AuthenticationMethod,
    val isInternal: Boolean
)




interface RegistryMetadataResolver {
    fun getMetadataForRegistry(registry: String): RegistryMetadata
}

@Component
class DefaultRegistryMetadataResolver(@Value("\${cantus.docker.internalUrls}") val internalRegistryAddresses: List<String>) :
    RegistryMetadataResolver {

    override fun getMetadataForRegistry(registry: String): RegistryMetadata {

        val isInternalRegistry = internalRegistryAddresses.any { internalAddress -> registry == internalAddress }
        return if (isInternalRegistry) RegistryMetadata(
            registry, "http",
            AuthenticationMethod.KUBERNETES_TOKEN, isInternalRegistry
        )
        else RegistryMetadata(
            registry,
            "https",
            AuthenticationMethod.NONE,
            isInternalRegistry
        )
    }
}
