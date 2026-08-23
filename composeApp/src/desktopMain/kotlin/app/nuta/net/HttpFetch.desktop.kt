package app.nuta.net

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

actual suspend fun httpGet(url: String, headers: Map<String, String>): String {
    val builder = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).GET()
    headers.forEach { (key, value) -> builder.header(key, value) }
    val response = send(builder.build())
    require(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}: ${response.body()}" }
    return response.body()
}

actual suspend fun httpPost(url: String, headers: Map<String, String>, body: String): String {
    val builder = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20))
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
