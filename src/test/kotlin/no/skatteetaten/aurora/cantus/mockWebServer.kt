package no.skatteetaten.aurora.cantus

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType


fun MockWebServer.enqueueJson(status: Int = 200, body: Any) {
    val json = body as? String ?: ObjectMapper().writeValueAsString(body)
    val response = MockResponse()
            .setResponseCode(status)
            .setBody(json)
    this.enqueue(response)
}

fun MockWebServer.execute(status: Int, response: Any, fn: () -> Unit): RecordedRequest {
    this.enqueueJson(status, response)
    fn()
    return this.takeRequest()
}

fun MockWebServer.execute(response: MockResponse, fn: () -> Unit): RecordedRequest {
    this.enqueue(response)
    fn()
    return this.takeRequest()
}

fun MockWebServer.execute(vararg responses: MockResponse, fn: () -> Unit): List<RecordedRequest> {
    responses.forEach { this.enqueue(it) }
    fn()

    return (1..responses.size).toList().map { this.takeRequest() }
}


fun MockWebServer.execute(response: Any, fn: () -> Unit): RecordedRequest {
    this.enqueueJson(body = response)
    fn()
    return this.takeRequest()
}


fun MockResponse.setJsonFileAsBody(fileName: String): MockResponse {
    val classPath = ClassPathResource("/$fileName")
    val json = classPath.file.readText()
    this.addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_UTF8_VALUE)
    return this.setBody(json)
}