package no.skatteetaten.aurora.cantus.controller

import io.netty.handler.timeout.ReadTimeoutException
import mu.KotlinLogging
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import reactor.core.publisher.toMono
import reactor.retry.RetryExhaustedException
import java.time.Duration

private const val blockTimeout: Long = 30
private val logger = KotlinLogging.logger {}

fun <T> Mono<T>.blockAndHandleError(
    duration: Duration = Duration.ofSeconds(blockTimeout),
    imageRepoCommand: ImageRepoCommand? = null
) =
    this.handleError(imageRepoCommand).toMono().block(duration)

fun <T> Mono<T>.handleError(imageRepoCommand: ImageRepoCommand?) =
    this.doOnError {
        when (it) {
            is WebClientResponseException -> it.handleException(imageRepoCommand)
            is ReadTimeoutException -> it.handleException(imageRepoCommand)
            is SourceSystemException -> it.logAndRethrow()
            is RetryExhaustedException -> it.handleException(imageRepoCommand)
            else -> it.handleException()
        }
    }

private fun RetryExhaustedException.handleException(imageRepoCommand: ImageRepoCommand?) {
    val msg = "Retry failed after 4 attempts url=${imageRepoCommand?.fullRepoCommand}\""
    logger.error(this) { msg }
    throw SourceSystemException(
        message = msg,
        cause = this,
        sourceSystem = imageRepoCommand?.registry
    )
}
private fun WebClientResponseException.handleException(imageRepoCommand: ImageRepoCommand?) {
    val msg = "Error in response, status=$statusCode message=$statusText body=\"${this.responseBodyAsString}\""
    logger.error(this) { msg }
    throw SourceSystemException(
        message = msg,
        cause = this,
        sourceSystem = imageRepoCommand?.registry
    )
}

private fun ReadTimeoutException.handleException(imageRepoCommand: ImageRepoCommand?) {
    val imageMsg = imageRepoCommand?.let { cmd ->
        "registry=\"${cmd.registry}\" imageGroup=\"${cmd.imageGroup}\" imageName=\"${cmd.imageName}\" imageTag=\"${cmd.imageTag}\""
    } ?: "no existing ImageRepoCommand"
    val msg = "Timeout when calling docker registry, $imageMsg"
    logger.error(this) { msg }
    throw SourceSystemException(
        message = msg,
        cause = this,
        sourceSystem = imageRepoCommand?.registry
    )
}

private fun Throwable.logAndRethrow() {
    logger.error(this) {}
    throw this
}

private fun Throwable.handleException() {
    val msg = "Error in response or request (${this::class.simpleName})"
    logger.error(this) { msg }
    throw CantusException(msg, this)
}

fun <T> ClientResponse.handleStatusCodeError(sourceSystem: String?): Mono<T> {
    val statusCode = this.statusCode()
    return this.bodyToMono<String>().switchIfEmpty(Mono.just("")).flatMap { body ->
        Mono.error<T>(
            SourceSystemException(
                message = "body=$body status=${statusCode.value()} message=${statusCode.reasonPhrase}",
                sourceSystem = sourceSystem
            )
        )
    }
}
