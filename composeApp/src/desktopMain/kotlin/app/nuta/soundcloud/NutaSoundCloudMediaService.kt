package app.nuta.soundcloud

import app.nuta.core.logging.NutaLogger
import app.nuta.core.models.Track
import app.nuta.core.security.SecretValue
import app.nuta.settings.PlaybackSettingsStore
import app.nuta.youtube.AudioFormatSelector
import app.nuta.youtube.AudioStreamSource
import app.nuta.youtube.YouTubeCandidate
import app.nuta.youtube.YouTubeMatch
import app.nuta.youtube.YouTubeMediaService
import app.nuta.youtube.YouTubeResolution
import app.nuta.youtube.rankCandidate
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/**
 * Desktopowy odpowiednik `AndroidSoundCloudMediaService` — ten sam flow (client_id →
 * search/tracks → transcoding → podpisany CDN URL), ale przez `java.net.http.HttpClient`
 * zamiast `HttpURLConnection`, tak jak `NutaYouTubeMediaService` robi to dla YouTube.
 * Zweryfikowane ręcznie curlem 22.08.2026 przed napisaniem tego kodu.
 */
class NutaSoundCloudMediaService(
    private val logger: NutaLogger,
    private val settingsStore: PlaybackSettingsStore,
) : YouTubeMediaService {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private var cachedClientId: String? = null

    override suspend fun resolve(track: Track): YouTubeResolution {
        val matches = search(track)
        val selected = matches.firstOrNull() ?: error("SoundCloud nie zwrócił kandydatów")
        val stream = resolveStream(selected.candidate.videoId)
        logger.info("SoundCloudResolver", "soundcloud_stream_selected", "Wybrano strumień audio SoundCloud", fields = mapOf(
            "codec" to stream.codec,
            "bitrate" to stream.bitrate.toString(),
            "score" to selected.score.toString(),
        ))
        return YouTubeResolution(selected, matches.drop(1), stream)
    }

    override suspend fun validate(stream: AudioStreamSource) {
        val request = stream.url.use { url ->
            HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20))
                .header("User-Agent", UserAgent)
                .header("Range", "bytes=0-4095")
                .GET().build()
        }
        val response = send(request, HttpResponse.BodyHandlers.ofByteArray())
        require(response.statusCode() == 200 || response.statusCode() == 206) { "Walidacja strumienia HTTP ${response.statusCode()}" }
        require(response.body().isNotEmpty()) { "Strumień zwrócił pustą odpowiedź" }
    }

    private suspend fun search(track: Track): List<YouTubeMatch> {
        val query = listOf(track.artists.firstOrNull().orEmpty(), track.title).filter(String::isNotBlank).joinToString(" ")
        val clientId = clientId()
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val body = getText("https://api-v2.soundcloud.com/search/tracks?q=$encoded&client_id=$clientId&limit=20")
        val collection = json.parseToJsonElement(body).jsonObject["collection"] as? JsonArray ?: return emptyList()
        return collection.mapNotNull(::candidate).map { candidate -> rank(track, candidate) }.sortedByDescending(YouTubeMatch::score)
    }

    private fun candidate(raw: JsonElement): YouTubeCandidate? = runCatching {
        val item = raw.jsonObject
        val id = item["id"]?.jsonPrimitive?.long ?: return null
        val title = item["title"]?.jsonPrimitive?.contentOrNull ?: return null
        val user = item["user"]?.jsonObject?.get("username")?.jsonPrimitive?.contentOrNull.orEmpty()
        val durationMs = item["full_duration"]?.jsonPrimitive?.longOrNull ?: item["duration"]?.jsonPrimitive?.longOrNull
        val hasProgressive = (item["media"]?.jsonObject?.get("transcodings") as? JsonArray)
            ?.any { it.jsonObject["format"]?.jsonObject?.get("protocol")?.jsonPrimitive?.contentOrNull == "progressive" } == true
        if (!hasProgressive) return null
        val verified = item["user"]?.jsonObject?.get("verified")?.jsonPrimitive?.contentOrNull == "true"
        YouTubeCandidate(id.toString(), title, user, durationMs, isOfficial = verified)
    }.getOrNull()

    private fun rank(track: Track, candidate: YouTubeCandidate): YouTubeMatch {
        val (score, reasons) = rankCandidate(track, candidate.title, candidate.channel, candidate.durationMs, candidate.isOfficial)
        return YouTubeMatch(candidate, score, reasons)
    }

    private suspend fun resolveStream(trackId: String): AudioStreamSource {
        val clientId = clientId()
        val body = getText("https://api-v2.soundcloud.com/tracks/$trackId?client_id=$clientId")
        val track = json.parseToJsonElement(body).jsonObject
        val transcodings = (track["media"]?.jsonObject?.get("transcodings") as? JsonArray) ?: error("SoundCloud: brak transcodings")
        val progressive = transcodings.map(JsonElement::jsonObject)
            .filter { it["format"]?.jsonObject?.get("protocol")?.jsonPrimitive?.contentOrNull == "progressive" }
        if (progressive.isEmpty()) error("SoundCloud: brak formatu progressive dla tego utworu")
        val resolved = progressive.mapNotNull { resolveTranscoding(it, clientId) }
        val settings = settingsStore.settings.value
        return AudioFormatSelector.select(resolved, settings.quality, settings.codec) ?: error("SoundCloud: nie udało się rozwiązać żadnego formatu")
    }

    private suspend fun resolveTranscoding(transcoding: JsonObject, clientId: String): AudioStreamSource? = runCatching {
        val transcodingUrl = transcoding["url"]?.jsonPrimitive?.contentOrNull ?: return null
        val mime = transcoding["format"]?.jsonObject?.get("mime_type")?.jsonPrimitive?.contentOrNull ?: "audio/mpeg"
        val preset = transcoding["preset"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val body = getText("$transcodingUrl?client_id=$clientId")
        val cdnUrl = json.parseToJsonElement(body).jsonObject["url"]?.jsonPrimitive?.contentOrNull ?: return null
        format(cdnUrl, mime, preset)
    }.getOrElse {
        logger.warn("SoundCloudResolver", "transcoding_resolve_failed", "Nie udało się rozwiązać transkodowania", fields = mapOf("reason" to (it.message ?: "unknown")))
        null
    }

    private fun format(url: String, mime: String, preset: String): AudioStreamSource {
        val codec = if (preset.startsWith("opus") || mime.contains("ogg")) "opus" else "mp3"
        val bitrate = if (codec == "opus") 64_000 else 128_000
        return AudioStreamSource(SecretValue.of(url), mime, mime.substringAfter("audio/").substringBefore(';'), codec, bitrate, null, decodeCloudFrontExpiry(url))
    }

    private fun decodeCloudFrontExpiry(url: String): Long? = runCatching {
        val policy = Regex("[?&]Policy=([^&]+)").find(url)?.groupValues?.get(1) ?: return null
        val standardBase64 = policy.replace("_", "=").replace("~", "/").replace("-", "+")
        val decoded = String(Base64.getDecoder().decode(standardBase64))
        Regex("\"AWS:EpochTime\"\\s*:\\s*(\\d+)").find(decoded)?.groupValues?.get(1)?.toLongOrNull()?.times(1_000)
    }.getOrNull()

    private suspend fun clientId(): String {
        cachedClientId?.let { return it }
        val home = getText("https://soundcloud.com")
        val bundleUrls = Regex("src=\"(https://a-v2\\.sndcdn\\.com/assets/[^\"]+\\.js)\"").findAll(home).map { it.groupValues[1] }.toList()
        for (bundleUrl in bundleUrls) {
            val js = runCatching { getText(bundleUrl) }.getOrNull() ?: continue
            val match = Regex("client_id\\s*:\\s*\"([a-zA-Z0-9]+)\"").find(js)?.groupValues?.get(1)
            if (match != null) { cachedClientId = match; return match }
        }
        error("SoundCloud: nie znaleziono client_id w bundlach JS")
    }

    private suspend fun getText(url: String): String = send(
        HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", UserAgent).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    ).let { response -> require(response.statusCode() in 200..299) { "SoundCloud HTTP ${response.statusCode()}" }; response.body() }

    private suspend fun <T> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> = withContext(Dispatchers.IO) {
        client.send(request, handler)
    }

    private companion object {
        const val UserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/128.0 Safari/537.36"
    }
}
