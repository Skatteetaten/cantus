package no.skatteetaten.aurora.cantus.controller

class NoSuchResourceException(message: String) : RuntimeException(message)
class DockerRegistryException(message: String) : RuntimeException(message)
class BadRequestException(message: String) : RuntimeException(message)
open class CantusException(message: String,
    cause: Throwable? = null,
    val code: String = "",
    val errorMessage: String = message): java.lang.RuntimeException(message, cause)

class SourceSystemException(
    message: String,
    cause: Throwable? = null,
    code: String = "",
    errorMessage: String = message,
    val sourceSystem: String? = null
) : CantusException(message, cause, code, errorMessage)