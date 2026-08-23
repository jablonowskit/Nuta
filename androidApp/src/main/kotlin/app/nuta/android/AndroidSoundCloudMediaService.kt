package app.nuta.android

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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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
 * Alternatywny resolver audio przez nieoficjalne, publiczne API SoundCloud (api-v2.soundcloud.com).
 * Jawnie wybierany w Ustawieniach (AudioSource.SOUNDCLOUD) — nie automatyczny fallback po błędzie
 * YouTube. Zabezpieczenie na wypadek, gdy YouTube wymusi SABR na VISIONOS tak jak zrobił to
 * wcześniej z WEB/TVHTML5/ANDROID_VR — patrz openspec/changes/2026-08-sabr-blocker/.
 *
 * Cały flow (client_id → search/tracks → transcoding → podpisany CDN URL → GET z Range)
 * zweryfikowany ręcznie curlem 22.08.2026 przed napisaniem tego kodu.
 */
class AndroidSoundCloudMediaService(
    private val logger: NutaLogger,
    private val settingsStore: PlaybackSettingsStore,
) : YouTubeMediaService {
    private val json = Json { ignoreUnknownKeys = true }
    /** client_id jest stabilny między wyszukiwaniami — zmienia się tylko przy nowym wydaniu
        bundla JS SoundCloud, więc jeden slot cache oszczędza scrapowanie strony na każdy utwór. */
    private var cachedClientId: String? = null

    override suspend fun resolve(track: Track): YouTubeResolution {
        val matches = search(track)
        val selected = matches.firstOrNull() ?: error("SoundCloud nie zwrócił kandydatów")
        val stream = resolveStream(selected.candidate.videoId)
        logger.info("AndroidSoundCloud", "stream_resolved", "Wybrano strumień audio SoundCloud", fields = mapOf(
            "codec" to stream.codec,
            "mimeType" to stream.mimeType,
            "bitrate" to stream.bitrate.toString(),
            "score" to selected.score.toString(),
        ))
        return YouTubeResolution(selected, matches.drop(1), stream)
    }

    override suspend fun validate(stream: AudioStreamSource) {
        val url = stream.url.use { it }
        request(url, range = true)
    }

    private suspend fun search(track: Track): List<YouTubeMatch> {
        val query = listOf(track.artists.firstOrNull().orEmpty(), track.title).filter(String::isNotBlank).joinToString(" ")
        val clientId = clientId()
        val body = request("https://api-v2.soundcloud.com/search/tracks?q=${URLEncoder.encode(query, "UTF-8")}&client_id=$clientId&limit=20")
        val collection = json.parseToJsonElement(body).jsonObject["collection"] as? JsonArray ?: return emptyList()
        return collection.mapNotNull(::candidate).map { candidate -> rank(track, candidate) }.sortedByDescending(YouTubeMatch::score)
    }

    /** id trzymamy w polu `videoId` (nazwa dziedziczona z YouTube-owego kontraktu, ale samo
        pole jest po prostu identyfikatorem źródła — używane przez resolveStream do dociągnięcia
        pełnego obiektu utworu). Tylko kandydaci z co najmniej jednym transkodowaniem
        "progressive" (zwykły plik) — HLS nie jest wsparte, patrz plan. */
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
        val body = request("https://api-v2.soundcloud.com/tracks/$trackId?client_id=$clientId")
        val track = json.parseToJsonElement(body).jsonObject
        val transcodings = (track["media"]?.jsonObject?.get("transcodings") as? JsonArray) ?: error("SoundCloud: brak transcodings")
        val progressive = transcodings.map(JsonElement::jsonObject)
            .filter { it["format"]?.jsonObject?.get("protocol")?.jsonPrimitive?.contentOrNull == "progressive" }
        if (progressive.isEmpty()) error("SoundCloud: brak formatu progressive dla tego utworu")
        val resolved = progressive.mapNotNull { resolveTranscoding(it, clientId) }
        return selectFormat(resolved) ?: error("SoundCloud: nie udało się rozwiązać żadnego formatu")
    }

    private suspend fun resolveTranscoding(transcoding: JsonObject, clientId: String): AudioStreamSource? = runCatching {
        val transcodingUrl = transcoding["url"]?.jsonPrimitive?.contentOrNull ?: return null
        val mime = transcoding["format"]?.jsonObject?.get("mime_type")?.jsonPrimitive?.contentOrNull ?: "audio/mpeg"
        val preset = transcoding["preset"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val body = request("$transcodingUrl?client_id=$clientId")
        val cdnUrl = json.parseToJsonElement(body).jsonObject["url"]?.jsonPrimitive?.contentOrNull ?: return null
        format(cdnUrl, mime, preset)
    }.getOrElse {
        logger.warn("AndroidSoundCloud", "transcoding_resolve_failed", "Nie udało się rozwiązać transkodowania", fields = mapOf("reason" to (it.message ?: "unknown")))
        null
    }

    private fun format(url: String, mime: String, preset: String): AudioStreamSource {
        val codec = if (preset.startsWith("opus") || mime.contains("ogg")) "opus" else "mp3"
        // SoundCloud nie podaje realnego bitrate w API — presety "mp3_1_0"/"opus_0_0" to
        // standardowe wartości dla darmowego, niezalogowanego dostępu.
        val bitrate = if (codec == "opus") 64_000 else 128_000
        return AudioStreamSource(
            url = SecretValue.of(url),
            mimeType = mime,
            container = mime.substringAfter("audio/").substringBefore(';'),
            codec = codec,
            bitrate = bitrate,
            contentLength = null,
            expiresAtMs = decodeCloudFrontExpiry(url),
        )
    }

    /** URL-e CDN SoundCloud (`cf-media.sndcdn.com`) są podpisane CloudFront Policy — base64
        (alfabet `+_/` → `-_~`) zawierającym `Condition.DateLessThan.AWS:EpochTime`. Zweryfikowane
        ręcznie 22.08.2026. Best-effort — brak Policy (np. inny CDN) po prostu daje `null`. */
    private fun decodeCloudFrontExpiry(url: String): Long? = runCatching {
        val policy = Regex("[?&]Policy=([^&]+)").find(url)?.groupValues?.get(1) ?: return null
        val standardBase64 = policy.replace("_", "=").replace("~", "/").replace("-", "+")
        val decoded = String(Base64.getDecoder().decode(standardBase64))
        Regex("\"AWS:EpochTime\"\\s*:\\s*(\\d+)").find(decoded)?.groupValues?.get(1)?.toLongOrNull()?.times(1_000)
    }.getOrNull()

    private fun selectFormat(formats: List<AudioStreamSource>): AudioStreamSource? {
        val settings = settingsStore.settings.value
        return AudioFormatSelector.select(formats, settings.quality, settings.codec)
    }

    private suspend fun clientId(): String {
        cachedClientId?.let { return it }
        val home = request("https://soundcloud.com")
        val bundleUrls = Regex("src=\"(https://a-v2\\.sndcdn\\.com/assets/[^\"]+\\.js)\"").findAll(home).map { it.groupValues[1] }.toList()
        for (bundleUrl in bundleUrls) {
            val js = runCatching { request(bundleUrl) }.getOrNull() ?: continue
            val match = Regex("client_id\\s*:\\s*\"([a-zA-Z0-9]+)\"").find(js)?.groupValues?.get(1)
            if (match != null) { cachedClientId = match; return match }
        }
        error("SoundCloud: nie znaleziono client_id w bundlach JS")
    }

    private suspend fun request(url: String, range: Boolean = false): String = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000; connection.readTimeout = 25_000; connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (range) connection.setRequestProperty("Range", "bytes=0-4095")
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            require(status in 200..299) { "SoundCloud HTTP $status" }
            response
        } finally { connection.disconnect() }
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/128.0 Safari/537.36"
    }
}
