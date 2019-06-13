package no.skatteetaten.aurora.cantus.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

data class RegistryMetadata(
    val registry: String,
    val apiSchema: String,
    val isInternal: Boolean,
    val port: String? = null
) {
    val fullRegistryUrl: String
        get() = "$apiSchema://$registry${if (port.isNullOrEmpty()) "" else ":$port"}/v2"
}

@Component
class RegistryMetadataResolver(
    @Value("\${cantus.docker.internal.urls}") val internalRegistryAddresses: List<String>
) {

    fun http(registry: String) = RegistryMetadata(
        registry = registry,
        apiSchema = "http",
        isInternal = true
    )

    fun https(registry: String) = RegistryMetadata(
        registry = registry,
        apiSchema = "https",
        isInternal = false
    )

    fun getMetadataForRegistry(registry: String): RegistryMetadata {

        val ipV4WithPortRegex =
            "^((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\\.){3}(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9]):[0-9]{1,4}\$".toRegex()

        val isInternal =
            internalRegistryAddresses.any { it == registry } || registry.matches(ipV4WithPortRegex)

        return if (isInternal) {
            this.http(registry)
        } else {
            this.https(registry)
        }
    }
}
