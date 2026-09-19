package app.nuta.net

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// connectTimeout jest per-request (patrz httpGet/httpPost), nie per-client — HttpClient
// współdzielony między wszystkimi wywołaniami niezależnie od timeoutMs.
private val client = HttpClient.newBuilder().build()

actual suspend fun httpGet(url: String, headers: Map<String, String>, timeoutMs: Int): String {
    val builder = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMillis(timeoutMs.toLong())).GET()
    headers.forEach { (key, value) -> builder.header(key, value) }
    val response = send(builder.build())
    require(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}: ${response.body()}" }
    return response.body()
}

actual suspend fun httpPost(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): String {
    val builder = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMillis(timeoutMs.toLong()))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
    headers.forEach { (key, value) -> builder.header(key, value) }
    val response = send(builder.build())
    require(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}: ${response.body()}" }
    return response.body()
}

private suspend fun send(request: HttpRequest): HttpResponse<String> = withContext(Dispatchers.IO) {
    client.send(request, HttpResponse.BodyHandlers.ofString())
}
