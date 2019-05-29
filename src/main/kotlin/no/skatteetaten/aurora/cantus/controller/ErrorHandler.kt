package no.skatteetaten.aurora.cantus.controller

import io.netty.handler.timeout.ReadTimeoutException
import mu.KotlinLogging
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import reactor.core.publisher.toMono
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
            is WebClientResponseException -> createWebClientException(it, imageRepoCommand)
            is ReadTimeoutException -> createTimeoutException(it, imageRepoCommand)
            is SourceSystemException -> rethrowSourceSystemException(it)
            else -> createCantusException(it)
        }
    }

private fun createWebClientException(e: WebClientResponseException, imageRepoCommand: ImageRepoCommand?) {
    val msg = "Error in response, status=${e.statusCode} message=${e.statusText}"
    logger.error(e) { msg }
    throw SourceSystemException(
        message = msg,
        cause = e,
        sourceSystem = imageRepoCommand?.registry
    )
}

private fun createTimeoutException(t: Throwable?, imageRepoCommand: ImageRepoCommand?) {
    val imageMsg = imageRepoCommand?.let { cmd ->
        "imageGroup=\"${cmd.imageGroup}\" imageName=\"${cmd.imageName}\" imageTag=\"${cmd.imageTag}\""
    } ?: "no existing ImageRepoCommand"
    val msg = "Timeout when calling docker registry, $imageMsg"
    logger.error(t) { msg }
    throw SourceSystemException(
        message = msg,
        cause = t,
        sourceSystem = imageRepoCommand?.registry
    )
}

private fun rethrowSourceSystemException(it: Throwable) {
    logger.error(it) {}
    throw it
}

private fun createCantusException(it: Throwable) {
    val msg = "Error in response or request (${it::class.simpleName})"
    logger.error(it) { msg }
    throw CantusException(msg, it)
}

fun ClientResponse.handleStatusCodeError(sourceSystem: String?) {

    val statusCode = this.statusCode()

    if (statusCode.is2xxSuccessful) {
        return
    }

    val message = when {
        statusCode.is4xxClientError -> {
            when (statusCode.value()) {
                404 -> "Resource could not be found"
                400 -> "Invalid request"
                403 -> "Forbidden"
                else -> "There has occurred a client error"
            }
        }
        statusCode.is5xxServerError -> {
            when (statusCode.value()) {
                500 -> "An internal server error has occurred in the docker registry"
                else -> "A server error has occurred"
            }
        }

        else ->
            "Unknown error occurred"
    }

    throw SourceSystemException(
        message = "$message status=${statusCode.value()} message=${statusCode.reasonPhrase}",
        sourceSystem = sourceSystem
    )
}
