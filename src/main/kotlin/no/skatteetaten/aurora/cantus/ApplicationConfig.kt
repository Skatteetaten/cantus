package no.skatteetaten.aurora.cantus

import io.netty.channel.ChannelOption
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import kotlinx.coroutines.newFixedThreadPoolContext
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.netty.http.client.HttpClient
import reactor.netty.tcp.SslProvider
import reactor.netty.tcp.TcpClient
import java.io.FileInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.TrustManagerFactory
import kotlin.math.min

enum class ServiceTypes {
    DOCKER, NEXUS, NEXUS_TOKEN
}

@Target(AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Qualifier
annotation class TargetService(val value: ServiceTypes)

private val logger = KotlinLogging.logger {}

private const val MAX_ACCEPTED_TOKEN_LENGTH = 11

@Configuration
@ConfigurationPropertiesScan
class ApplicationConfig {

    @Bean
    fun threadPoolContext(@Value("\${cantus.threadPoolSize:6}") threadPoolSize: Int) =
        newFixedThreadPoolContext(threadPoolSize, "cantus")

    @Bean
    @TargetService(ServiceTypes.DOCKER)
    fun webClientDocker(
        builder: WebClient.Builder,
        tcpClient: TcpClient,
        @Value("\${spring.application.name}") applicationName: String,
        @Value("\${application.version:}") applicationVersion: String
    ) =
        builder.filter(
            ExchangeFilterFunction.ofRequestProcessor {
                val bearer = it.headers()[HttpHeaders.AUTHORIZATION]?.firstOrNull()?.let { token ->
                    val (bearer, tokenValue) = token.substring(0, min(token.length, MAX_ACCEPTED_TOKEN_LENGTH))
                        .split(" ")
                    "$bearer=$tokenValue"
                } ?: ""

                logger.debug("HttpRequest method=${it.method()} url=${it.url()} $bearer")

                it.toMono()
            }
        ).clientConnector(
            ReactorClientHttpConnector(
                HttpClient
                    .from(tcpClient)
                    .compress(true)
            )
        ).build()

    @Bean
    fun objectMapper() = createObjectMapper()

    @Bean
    fun tcpClient(
        @Value("\${cantus.httpclient.readTimeout:5000}") readTimeout: Long,
        @Value("\${cantus.httpclient.writeTimeout:5000}") writeTimeout: Long,
        @Value("\${cantus.httpclient.connectTimeout:5000}") connectTimeout: Int,
        trustStore: KeyStore?
    ): TcpClient {
        val trustFactory = TrustManagerFactory.getInstance("X509")
        trustFactory.init(trustStore)

        val sslProvider = SslProvider.builder().sslContext(
            SslContextBuilder
                .forClient()
                .trustManager(trustFactory)
                .build()
        ).build()
        return TcpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout)
            .secure(sslProvider)
            .doOnConnected { connection ->
                connection
                    .addHandlerLast(ReadTimeoutHandler(readTimeout, TimeUnit.MILLISECONDS))
                    .addHandlerLast(WriteTimeoutHandler(writeTimeout, TimeUnit.MILLISECONDS))
            }
    }

    @Bean
    @TargetService(ServiceTypes.NEXUS)
    fun webClientNexus(
        webClientBuilder: WebClient.Builder,
        @Value("\${integrations.nexus.url}") nexusUrl: String,
    ): WebClient {
        logger.info("Configuring open Nexus WebClient with baseUrl={}", nexusUrl)
        return webClientBuilder.baseUrl(nexusUrl).build()
    }

    @ConditionalOnBean(RequiresNexusToken::class)
    @Bean
    @TargetService(ServiceTypes.NEXUS_TOKEN)
    fun webClientNexustoken(
        webClientBuilder: WebClient.Builder,
        @Value("\${integrations.nexus.url}") nexusUrl: String,
        @Value("\${integrations.nexus.token}") nexusToken: String,
    ): WebClient {
        logger.info("Configuring Nexus WebClient with baseUrl={} and token in headers", nexusUrl)
        return webClientBuilder.baseUrl(nexusUrl).filter(
            ExchangeFilterFunction.ofRequestProcessor { request ->
                Mono.just(
                    ClientRequest.from(request)
                        .header(
                            HttpHeaders.AUTHORIZATION,
                            "Basic $nexusToken"
                        )
                        .build()
                )
            }
        ).build()
    }

    @ConditionalOnMissingBean(KeyStore::class)
    @Bean
    fun localKeyStore(): KeyStore? = null

    @Profile("openshift")
    @Primary
    @Bean
    fun openshiftSSLContext(@Value("\${trust.store}") trustStoreLocation: String): KeyStore? =
        KeyStore.getInstance(KeyStore.getDefaultType())?.let { ks ->
            try {
                ks.load(FileInputStream(trustStoreLocation), "changeit".toCharArray())
                val fis = FileInputStream("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt")
                CertificateFactory.getInstance("X509").generateCertificates(fis).forEach {
                    ks.setCertificateEntry((it as X509Certificate).subjectX500Principal.name, it)
                }
                logger.debug("SSLContext successfully loaded")
            } catch (e: Exception) {
                logger.debug(e) { "SSLContext failed to load" }
                throw e
            }
            ks
        }
}

@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
@ConditionalOnProperty("integrations.nexus.token")
class RequiresNexusToken
