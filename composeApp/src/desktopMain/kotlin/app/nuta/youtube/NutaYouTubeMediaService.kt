package app.nuta.youtube

import app.nuta.core.logging.NutaLogger
import app.nuta.core.models.Track
import app.nuta.core.security.SecretValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class NutaYouTubeMediaService(
    private val logger: NutaLogger,
) : YouTubeMediaService {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override suspend fun resolve(track: Track): YouTubeResolution {
        val operationId = "youtube-${System.currentTimeMillis()}"
        logger.info("YouTubeSearch", "youtube_search_started", "Rozpoczęto wyszukiwanie kandydata", operationId)
        return try {
            val matches = search(track)
            val selected = matches.firstOrNull() ?: error("YouTube nie zwrócił kandydatów")
            logger.info(
                "YouTubeSearch", "youtube_match_selected", "Wybrano dopasowanie YouTube", operationId,
                mapOf("score" to selected.score.toString(), "candidateCount" to matches.size.toString()),
            )
            val stream = resolveStream(selected.candidate.videoId, operationId)
            YouTubeResolution(selected, matches.drop(1), stream)
        } catch (error: Throwable) {
            logger.error("YouTubeResolver", "youtube_resolution_failed", "Nie udało się uzyskać strumienia YouTube", operationId, throwable = error)
            throw error
        }
    }

    override suspend fun validate(stream: AudioStreamSource) {
        logger.info("YouTubeResolver", "youtube_stream_validation_started", "Rozpoczęto walidację strumienia")
        val request = stream.url.use { url ->
            HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20))
                .header("User-Agent", UserAgent)
                .header("Range", "bytes=0-4095")
                .GET().build()
        }
        val response = send(request, HttpResponse.BodyHandlers.ofByteArray())
        require(response.statusCode() == 200 || response.statusCode() == 206) {
            "Walidacja strumienia HTTP ${response.statusCode()}"
        }
        require(response.body().isNotEmpty()) { "Strumień zwrócił pustą odpowiedź" }
        logger.info(
            "YouTubeResolver", "youtube_stream_validation_completed", "Strumień audio jest dostępny",
            fields = mapOf("statusCode" to response.statusCode().toString(), "receivedBucket" to "small"),
        )
    }

    private suspend fun search(track: Track): List<YouTubeMatch> {
        val base = listOf(track.artists.firstOrNull().orEmpty(), track.title).filter(String::isNotBlank).joinToString(" ")
        val candidates = listOf("$base official audio", "$base lyrics").flatMap { query ->
            val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
            val html = getText("https://www.youtube.com/results?search_query=$encoded&hl=en&gl=US")
            extractObjects(html, "\"videoRenderer\":").mapNotNull(::parseCandidate)
        }
            .distinctBy(YouTubeCandidate::videoId)
            .take(30)
        val matches = candidates.map { candidate -> rank(track, candidate) }.sortedByDescending(YouTubeMatch::score)
        logger.info(
            "YouTubeSearch", "youtube_search_completed", "Zakończono wyszukiwanie kandydatów",
            fields = mapOf("candidateCount" to matches.size.toString()),
        )
        return matches
    }

    private fun parseCandidate(raw: String): YouTubeCandidate? = runCatching {
        val item = json.parseToJsonElement(raw).jsonObject
        val videoId = item["videoId"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = text(item["title"] as? JsonObject) ?: return null
        val channel = text(item["ownerText"] as? JsonObject).orEmpty()
        val duration = text(item["lengthText"] as? JsonObject)?.let(::parseDuration)
        if (duration == null) return null
        val normalized = "$title $channel".lowercase()
        YouTubeCandidate(
            videoId = videoId,
            title = title,
            channel = channel,
            durationMs = duration,
            isOfficial = channel.lowercase().endsWith(" - topic") || "official audio" in normalized,
        )
    }.getOrNull()

    private fun rank(track: Track, candidate: YouTubeCandidate): YouTubeMatch {
        val expectedTitle = normalize(track.title)
        val actualTitle = normalize(candidate.title)
        val artist = normalize(track.artists.firstOrNull().orEmpty())
        val haystack = normalize(candidate.title + " " + candidate.channel)
        var score = 0
        val reasons = mutableListOf<String>()
        if (actualTitle.contains(expectedTitle) || expectedTitle.contains(actualTitle)) { score += 45; reasons += "title" }
        if (artist.isNotBlank() && haystack.contains(artist)) { score += 30; reasons += "artist" }
        candidate.durationMs?.let {
            val difference = kotlin.math.abs(it - track.durationMs)
            when {
                difference <= 3_000 -> { score += 25; reasons += "duration_exact" }
                difference <= 10_000 -> { score += 12; reasons += "duration_close" }
                difference >= 45_000 -> { score -= 25; reasons += "duration_bad" }
            }
        }
        if (candidate.isOfficial) { score += 15; reasons += "official" }
        val lyric = Regex("\\blyrics?\\b").containsMatchIn(actualTitle)
        val officialLyric = lyric && ("official lyric" in actualTitle || "official lyrics" in actualTitle)
        if (officialLyric) { score += 12; reasons += "official_lyrics" }
        else if (lyric) { score += 5; reasons += "lyrics" }
        val penalties = mapOf(" live" to 35, "cover" to 35, "remix" to 25, "karaoke" to 40, "sped up" to 35, "slowed" to 30, "nightcore" to 40)
        penalties.forEach { (word, penalty) -> if (word in " $haystack") { score -= penalty; reasons += "penalty_${word.trim().replace(' ', '_')}" } }
        return YouTubeMatch(candidate, score, reasons)
    }

    private suspend fun resolveStream(videoId: String, operationId: String): AudioStreamSource {
        logger.info("YouTubeResolver", "youtube_player_request_started", "Pobieranie danych playera", operationId)
        val watchHtml = getText("https://www.youtube.com/watch?v=$videoId&hl=en&gl=US")
        val apiKey = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"").find(watchHtml)?.groupValues?.get(1)
            ?: error("Brak klucza klienta YouTube")
        val clientVersion = Regex("\"INNERTUBE_CONTEXT_CLIENT_VERSION\":\"([^\"]+)\"").find(watchHtml)?.groupValues?.get(1)
            ?: error("Brak wersji klienta YouTube")
        val visitorData = Regex("\"VISITOR_DATA\":\"([^\"]+)\"").find(watchHtml)?.groupValues?.get(1)
            ?: Regex("\"visitorData\":\"([^\"]+)\"").find(watchHtml)?.groupValues?.get(1)
        val profiles = listOf(
            PlayerProfile("WEB", clientVersion, "1", UserAgent, emptyMap()),
            PlayerProfile(
                "ANDROID_VR", "1.65.10", "28",
                "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                mapOf("deviceMake" to "Oculus", "deviceModel" to "Quest 3", "androidSdkVersion" to 32, "osName" to "Android", "osVersion" to "12L"),
            ),
            PlayerProfile("WEB_EMBEDDED_PLAYER", clientVersion, "56", UserAgent, emptyMap(), embedded = true),
        )
        var root: JsonObject? = null
        var lastStatus = "UNKNOWN"
        for (profile in profiles) {
            val candidate = requestPlayer(apiKey, videoId, visitorData, profile)
            lastStatus = candidate["playabilityStatus"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull ?: "UNKNOWN"
            logger.info(
                "YouTubeResolver", "youtube_player_response_received", "Odebrano odpowiedź profilu playera", operationId,
                mapOf("profile" to profile.name, "playability" to lastStatus),
            )
            if (lastStatus == "OK") { root = candidate; break }
        }
        val playerRoot = requireNotNull(root) { "YouTube playability: $lastStatus" }
        val formats = playerRoot["streamingData"]?.jsonObject?.get("adaptiveFormats") as? JsonArray ?: error("Brak formatów adaptacyjnych")
        val audio = formats.mapNotNull { parseAudioFormat(it.jsonObject) }
        val selected = audio.maxWithOrNull(compareBy<AudioStreamSource>({ if (it.codec.contains("opus", true)) 1 else 0 }, AudioStreamSource::bitrate))
            ?: if (formats.any { it.jsonObject["signatureCipher"] != null || it.jsonObject["cipher"] != null }) {
                error("YouTube wymaga transformacji podpisu dla dostępnych formatów")
            } else error("Brak bezpośredniego formatu audio-only")
        logger.info(
            "YouTubeResolver", "youtube_stream_selected", "Wybrano strumień audio-only", operationId,
            mapOf("codec" to selected.codec, "container" to selected.container, "bitrateBucket" to bitrateBucket(selected.bitrate)),
        )
        return selected
    }

    private suspend fun requestPlayer(apiKey: String, videoId: String, visitorData: String?, profile: PlayerProfile): JsonObject {
        val clientFields = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
            "clientName" to JsonPrimitive(profile.name),
            "clientVersion" to JsonPrimitive(profile.version),
            "hl" to JsonPrimitive("en"),
            "gl" to JsonPrimitive("US"),
        )
        profile.extra.forEach { (key, value) ->
            clientFields[key] = when (value) {
                is Int -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
        }
        if (!visitorData.isNullOrBlank()) clientFields["visitorData"] = JsonPrimitive(visitorData)
        val contextFields = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
            "client" to JsonObject(clientFields),
        )
        if (profile.embedded) {
            contextFields["thirdParty"] = JsonObject(mapOf("embedUrl" to JsonPrimitive("https://www.youtube.com/")))
        }
        val body = JsonObject(mapOf(
            "videoId" to JsonPrimitive(videoId),
            "contentCheckOk" to JsonPrimitive(true),
            "racyCheckOk" to JsonPrimitive(true),
            "context" to JsonObject(contextFields),
            "playbackContext" to JsonObject(mapOf("contentPlaybackContext" to JsonObject(mapOf(
                "html5Preference" to JsonPrimitive("HTML5_PREF_WANTS"),
            )))),
        )).toString()
        val requestBuilder = HttpRequest.newBuilder(URI("https://www.youtube.com/youtubei/v1/player?key=$apiKey"))
                .timeout(Duration.ofSeconds(20)).header("User-Agent", profile.userAgent)
                .header("X-YouTube-Client-Name", profile.id).header("X-YouTube-Client-Version", profile.version)
                .header("Content-Type", "application/json").header("Origin", "https://www.youtube.com")
                .POST(HttpRequest.BodyPublishers.ofString(body))
        if (!visitorData.isNullOrBlank()) requestBuilder.header("X-Goog-Visitor-Id", visitorData)
        val response = send(
            requestBuilder.build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        require(response.statusCode() in 200..299) { "YouTube player HTTP ${response.statusCode()}" }
        return json.parseToJsonElement(response.body()).jsonObject
    }

    private fun parseAudioFormat(item: JsonObject): AudioStreamSource? {
        val mime = item["mimeType"]?.jsonPrimitive?.contentOrNull ?: return null
        if (!mime.startsWith("audio/")) return null
        val url = item["url"]?.jsonPrimitive?.contentOrNull ?: return null
        val container = mime.substringAfter("audio/").substringBefore(';')
        val codec = Regex("codecs=\"([^\"]+)\"").find(mime)?.groupValues?.get(1).orEmpty()
        val bitrate = item["bitrate"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val length = item["contentLength"]?.jsonPrimitive?.content?.toLongOrNull()
        val expiry = Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1_000)
        return AudioStreamSource(SecretValue.of(url), mime, container, codec, bitrate, length, expiry)
    }

    private fun text(value: JsonObject?): String? {
        value ?: return null
        value["simpleText"]?.jsonPrimitive?.contentOrNull?.let { return it }
        return (value["runs"] as? JsonArray)?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
    }

    private fun extractObjects(source: String, marker: String): List<String> {
        val output = mutableListOf<String>()
        var searchFrom = 0
        while (output.size < 40) {
            val markerIndex = source.indexOf(marker, searchFrom)
            if (markerIndex < 0) break
            val start = source.indexOf('{', markerIndex + marker.length)
            if (start < 0) break
            var depth = 0
            var quoted = false
            var escaped = false
            var end = -1
            for (index in start until source.length) {
                val char = source[index]
                if (quoted) {
                    if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
                } else if (char == '"') quoted = true else if (char == '{') depth++ else if (char == '}' && --depth == 0) { end = index + 1; break }
            }
            if (end < 0) break
            output += source.substring(start, end)
            searchFrom = end
        }
        return output
    }

    private fun parseDuration(value: String): Long? {
        val parts = value.split(':').mapNotNull(String::toLongOrNull)
        if (parts.isEmpty()) return null
        return parts.fold(0L) { total, part -> total * 60 + part } * 1_000
    }

    private fun normalize(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    private fun bitrateBucket(value: Int) = when { value < 96_000 -> "low"; value < 160_000 -> "normal"; else -> "high" }

    private suspend fun getText(url: String): String = send(
        HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", UserAgent).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    ).let { response -> require(response.statusCode() in 200..299) { "YouTube HTTP ${response.statusCode()}" }; response.body() }

    private suspend fun <T> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> = withContext(Dispatchers.IO) {
        client.send(request, handler)
    }

    private companion object {
        const val UserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/128.0 Safari/537.36"
    }

    private data class PlayerProfile(
        val name: String,
        val version: String,
        val id: String,
        val userAgent: String,
        val extra: Map<String, Any>,
        val embedded: Boolean = false,
    )
}
