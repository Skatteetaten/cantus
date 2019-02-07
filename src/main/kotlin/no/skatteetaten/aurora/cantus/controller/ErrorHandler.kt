package no.skatteetaten.aurora.cantus.controller

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.context.request.WebRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import reactor.core.publisher.Mono
import reactor.core.publisher.toMono
import uk.q3c.rest.hal.HalResource
import java.time.Duration

val blockTimeout: Long = 30

@ControllerAdvice
class ErrorHandler : ResponseEntityExceptionHandler() {

    @ExceptionHandler(RuntimeException::class)
    fun handleGenericError(e: RuntimeException, request: WebRequest): ResponseEntity<Any>? {
        return handleException(e, request, HttpStatus.INTERNAL_SERVER_ERROR)
    }

    @ExceptionHandler(BadRequestException::class)
    fun handleBadRequest(e: BadRequestException, request: WebRequest): ResponseEntity<Any>? {
        return handleException(e, request, HttpStatus.BAD_REQUEST)
    }

    @ExceptionHandler(SourceSystemException::class)
    fun handleSourceSystem(e: SourceSystemException, request: WebRequest): ResponseEntity<Any>? {
        return handleException(e, request, HttpStatus.OK)
    }

    private fun handleException(e: Exception, request: WebRequest, httpStatus: HttpStatus): ResponseEntity<Any>? {
        val auroraResponse = AuroraResponse<HalResource>(
            success = false,
            message = e.message ?: "",
            exception = e
        )
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        logger.debug("Handle exception", e)
        return handleExceptionInternal(e, auroraResponse, headers, httpStatus, request)
    }
}

fun <T> Mono<T>.blockAndHandleError(
    duration: Duration = Duration.ofSeconds(blockTimeout),
    sourceSystem: String? = null
) =
    this.handleError(sourceSystem).toMono().block(duration)

fun <T> Mono<T>.handleError(sourceSystem: String?) =
    this.doOnError {
        when (it) {
            is WebClientResponseException -> throw SourceSystemException(
                message = "Error in response, status:${it.statusCode} message:${it.statusText}",
                cause = it,
                sourceSystem = sourceSystem,
                code = it.statusCode.name
            )
            is SourceSystemException -> throw it
            else -> throw CantusException("Unknown error in response or request", it)
        }
    }

fun ClientResponse.handleStatusCodeError(sourceSystem: String?): Throwable {
    val statusCode = this.statusCode()

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
        sourceSystem = sourceSystem,
        code = "200"
    )

    /*this.bodyToMono<JsonNode>().let {
        val exception = it as Throwable
        val message = when (it) {
            is WebClientResponseException -> {
                throw it
            }
            is RuntimeException -> throw it
            else -> {
                if (dockerContentDigest == null) {
                    "No docker content digest present on response"
                } else {
                    if (it is Throwable) {
                        throw CantusException(
                            message = "Error in response status=${statusCode.value()} message=${statusCode.reasonPhrase}",
                            cause = it,
                            code = statusCode.value().toString()
                        )
                    }else {
                        throw CantusException (
                            message = "Error in response status=${statusCode.value()} message=${statusCode.reasonPhrase}",
                            code = statusCode.value().toString(),
                            cause = null
                        )
                    }
                }
            }
        }


    }*/
}
