package no.skatteetaten.aurora.cantus

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component

@ConfigurationProperties("integrations")
@ConstructorBinding
data class AuroraIntegration(
    val docker: Map<String, DockerRegistry>
) {
    enum class AuthType { None, Basic, Bearer }

    data class DockerRegistry(
        val url: String,
        val guiUrlPattern: String?,
        val auth: AuthType? ,
        val https: Boolean,
        val readOnly: Boolean,
        val enabled: Boolean = false
    )
}

