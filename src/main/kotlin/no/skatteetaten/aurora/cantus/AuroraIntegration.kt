package no.skatteetaten.aurora.cantus

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@ConfigurationProperties("integrations")
@Component
data class AuroraIntegration(
    val docker: Map<String, DockerRegistry>
) {
    enum class AuthType { None, Basic, Bearer }

    data class DockerRegistry(
        val url: String,
        val guiUrlPattern: String? = null,
        val auth: AuthType,
        val https: Boolean,
        val readOnly: Boolean,
        val enabled: Boolean
    )
}

