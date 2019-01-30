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
    val fullRegistryUrl: String
        get() = "$apiSchema://$registry/v2"
}


@Component
class RegistryMetadataResolver(
    @Value("\${cantus.docker.internal.urls}") val internalRegistryAddresses: List<String>
) {

    fun getMetadataForRegistry(registry: String): RegistryMetadata {

        val isInternalRegistry = internalRegistryAddresses.any { internalAddress -> registry == internalAddress }
        val authMethod = if (isInternalRegistry) AuthenticationMethod.KUBERNETES_TOKEN else AuthenticationMethod.NONE
        val apiSchema = if (!isInternalRegistry) "https" else "http"

        return RegistryMetadata(
            registry = registry,
            apiSchema = apiSchema,
            authenticationMethod = authMethod,
            isInternal = isInternalRegistry
        )
    }
}
