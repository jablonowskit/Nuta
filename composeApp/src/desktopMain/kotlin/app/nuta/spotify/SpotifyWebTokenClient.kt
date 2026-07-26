package app.nuta.spotify

import app.nuta.core.logging.NutaLogger
import app.nuta.core.security.SecretValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Liczy token Spotify Web Playera POZA webview (tak jak Spotube), z jawnie
 * odczytanego cookie sp_dc (httpOnly — dostarcza SpotifyLoginHelperClient).
 * Ten sam publiczny mechanizm co dotychczasowy JS w SpotifyLoginPrototype,
 * tylko liczony po stronie Kotlina zamiast wewnątrz przeglądarki.
 */
class SpotifyWebTokenClient(private val logger: NutaLogger) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    suspend fun fetchToken(spDc: String): SpotifyWebToken = withContext(Dispatchers.IO) {
        val serverTimeSeconds = fetchServerTime()
        val (secret, version) = fetchTotpSecret()
        val totp = computeTotp(secret, serverTimeSeconds)

        val uri = URI(
            "https://open.spotify.com/api/token?reason=transport&productType=web-player" +
                "&totp=$totp&totpServer=$totp&totpVer=$version",
        )
        val request = HttpRequest.newBuilder(uri)
            .header("Cookie", "sp_dc=$spDc")
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "Spotify token endpoint HTTP ${response.statusCode()}" }

        val root = json.parseToJsonElement(response.body()).jsonObject
        val isAnonymous = root["isAnonymous"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        check(!isAnonymous) { "Sesja Spotify jest anonimowa — sp_dc wygasło albo jest nieprawidłowe" }
        val accessToken = root["accessToken"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val expiresAt = root["accessTokenExpirationTimestampMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        check(accessToken.isNotBlank() && expiresAt > System.currentTimeMillis() + 60_000) { "Nieprawidłowy token Spotify" }

        logger.info("SpotifyWebToken", "token_fetched", "Pobrano token Spotify Web Playera poza webview")
        SpotifyWebToken(SecretValue.of(accessToken), expiresAt)
    }

    private fun fetchServerTime(): Long {
        val request = HttpRequest.newBuilder(URI("https://open.spotify.com/api/server-time"))
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "server-time HTTP ${response.statusCode()}" }
        val root = json.parseToJsonElement(response.body()).jsonObject
        return root.getValue("serverTime").jsonPrimitive.content.toLong()
    }

    private fun fetchTotpSecret(): Pair<ByteArray, Int> {
        val request = HttpRequest.newBuilder(URI("https://api.github.com/gists/22ed9c6ba463899e933427f7de1f0eef"))
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "gist HTTP ${response.statusCode()}" }
        val gistRoot = json.parseToJsonElement(response.body()).jsonObject
        val nuancesContent = gistRoot.getValue("files").jsonObject.getValue("nuances.json").jsonObject
            .getValue("content").jsonPrimitive.content
        val nuances = json.parseToJsonElement(nuancesContent).jsonArray
        val newest = nuances.maxBy { it.jsonObject.getValue("v").jsonPrimitive.content.toInt() }.jsonObject
        val secretBase32 = newest.getValue("s").jsonPrimitive.content
        val version = newest.getValue("v").jsonPrimitive.content.toInt()
        return decodeBase32(secretBase32) to version
    }

    private fun computeTotp(secret: ByteArray, serverTimeSeconds: Long): String {
        val counter = serverTimeSeconds / 30
        val counterBytes = ByteArray(8)
        for (i in 0..7) {
            counterBytes[7 - i] = (counter shr (i * 8)).toByte()
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val digest = mac.doFinal(counterBytes)
        val offset = digest[digest.size - 1].toInt() and 0x0f
        val binary = ((digest[offset].toInt() and 0x7f) shl 24) or
            ((digest[offset + 1].toInt() and 0xff) shl 16) or
            ((digest[offset + 2].toInt() and 0xff) shl 8) or
            (digest[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    private fun decodeBase32(value: String): ByteArray {
        var buffer = 0
        var bits = 0
        val output = mutableListOf<Byte>()
        for (char in value.uppercase().filter { it != '=' && !it.isWhitespace() }) {
            val index = BASE32_ALPHABET.indexOf(char)
            require(index >= 0) { "invalid_base32" }
            buffer = (buffer shl 5) or index
            bits += 5
            if (bits >= 8) {
                bits -= 8
                output.add(((buffer shr bits) and 0xff).toByte())
            }
        }
        return output.toByteArray()
    }

    companion object {
        private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}
