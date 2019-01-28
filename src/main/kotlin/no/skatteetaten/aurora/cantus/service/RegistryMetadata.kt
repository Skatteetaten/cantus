package no.skatteetaten.aurora.cantus.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

enum class AuthenticationMethod { NONE, KUBERNETES_TOKEN }

data class RegistryMetadata(
    val registry: String,
    val apiSchema: String,
    val authenticationMethod: AuthenticationMethod,
    val isInternal: Boolean
) {
    val fullRegistryUrl : String
        get() = "$apiSchema://$registry"
}

interface RegistryMetadataResolver {
    fun getMetadataForRegistry(registry: String): RegistryMetadata
}

@Component
class DefaultRegistryMetadataResolver(@Value("\${cantus.docker.internal.urls}") val internalRegistryAddresses: List<String>) :
    RegistryMetadataResolver {

    override fun getMetadataForRegistry(registry: String): RegistryMetadata {

        val isInternalRegistry = internalRegistryAddresses.any { internalAddress -> registry == internalAddress }
        println(isInternalRegistry)

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
