package no.skatteetaten.aurora.cantus

open class SourceSystemException(message: String, cause: Throwable? = null, code: String = "", errorMessage: String = message) : RuntimeException(message, cause)