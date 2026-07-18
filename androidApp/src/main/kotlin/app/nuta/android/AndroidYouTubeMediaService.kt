package app.nuta.android

import app.nuta.core.logging.NutaLogger
import app.nuta.core.models.Track
import app.nuta.core.security.SecretValue
import app.nuta.youtube.AudioStreamSource
import app.nuta.youtube.YouTubeCandidate
import app.nuta.youtube.YouTubeMatch
import app.nuta.youtube.YouTubeMediaService
import app.nuta.youtube.YouTubeResolution
import app.nuta.settings.CodecPreference
import app.nuta.settings.PlaybackSettingsStore
import app.nuta.settings.StreamQuality
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AndroidYouTubeMediaService(
    private val logger: NutaLogger,
    private val settingsStore: PlaybackSettingsStore,
) : YouTubeMediaService {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun resolve(track: Track): YouTubeResolution {
        val matches = search(track)
        val selected = matches.firstOrNull() ?: error("YouTube nie zwrócił kandydatów")
        val stream = resolveStream(selected.candidate.videoId)
        logger.info("AndroidYouTube", "stream_resolved", "Wybrano strumień audio YouTube", fields = mapOf(
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
        val base = listOf(track.artists.firstOrNull().orEmpty(), track.title).filter(String::isNotBlank).joinToString(" ")
        val candidates = listOf("$base official audio", "$base lyrics").flatMap { query ->
            val html = request("https://www.youtube.com/results?search_query=${URLEncoder.encode(query, "UTF-8")}&hl=en&gl=US")
            extractObjects(html, "\"videoRenderer\":").mapNotNull(::candidate)
        }
        return candidates.distinctBy(YouTubeCandidate::videoId).take(30).map { rank(track, it) }.sortedByDescending(YouTubeMatch::score)
    }

    private fun candidate(raw: String): YouTubeCandidate? = runCatching {
        val item = json.parseToJsonElement(raw).jsonObject
        val duration = text(item["lengthText"] as? JsonObject)?.let(::duration) ?: return null
        val title = text(item["title"] as? JsonObject) ?: return null
        val channel = text(item["ownerText"] as? JsonObject).orEmpty()
        YouTubeCandidate(item["videoId"]?.jsonPrimitive?.contentOrNull ?: return null, title, channel, duration,
            channel.lowercase().endsWith(" - topic") || "official audio" in title.lowercase())
    }.getOrNull()

    private fun rank(track: Track, candidate: YouTubeCandidate): YouTubeMatch {
        val expected = normalize(track.title); val actual = normalize(candidate.title)
        val artist = normalize(track.artists.firstOrNull().orEmpty()); val haystack = normalize(candidate.title + " " + candidate.channel)
        var score = 0; val reasons = mutableListOf<String>()
        if (actual.contains(expected) || expected.contains(actual)) { score += 45; reasons += "title" }
        if (artist.isNotBlank() && artist in haystack) { score += 30; reasons += "artist" }
        candidate.durationMs?.let { val diff = kotlin.math.abs(it - track.durationMs); if (diff <= 3_000) score += 25 else if (diff <= 10_000) score += 12 else if (diff >= 45_000) score -= 25 }
        if (candidate.isOfficial) score += 15
        val lyric = Regex("\\blyrics?\\b").containsMatchIn(actual)
        val officialLyric = lyric && ("official lyric" in actual || "official lyrics" in actual)
        if (officialLyric) { score += 12; reasons += "official_lyrics" }
        else if (lyric) { score += 5; reasons += "lyrics" }
        listOf(" live" to 35, "cover" to 35, "remix" to 25, "karaoke" to 40, "sped up" to 35, "slowed" to 30).forEach { (word, penalty) -> if (word in " $haystack") score -= penalty }
        return YouTubeMatch(candidate, score, reasons)
    }

    private suspend fun resolveStream(videoId: String): AudioStreamSource {
        val watch = request("https://www.youtube.com/watch?v=$videoId&hl=en&gl=US")
        val key = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"").find(watch)?.groupValues?.get(1) ?: error("Brak klucza YouTube")
        val webVersion = Regex("\"INNERTUBE_CONTEXT_CLIENT_VERSION\":\"([^\"]+)\"").find(watch)?.groupValues?.get(1) ?: error("Brak wersji YouTube")
        val visitor = Regex("\"VISITOR_DATA\":\"([^\"]+)\"").find(watch)?.groupValues?.get(1)
        val profiles = listOf(Profile("WEB", webVersion, "1", USER_AGENT), Profile("ANDROID_VR", "1.65.10", "28", VR_AGENT))
        var last = "UNKNOWN"
        for (profile in profiles) {
            val client = mutableMapOf<String, JsonElement>("clientName" to JsonPrimitive(profile.name), "clientVersion" to JsonPrimitive(profile.version), "hl" to JsonPrimitive("en"), "gl" to JsonPrimitive("US"))
            visitor?.let { client["visitorData"] = JsonPrimitive(it) }
            if (profile.name == "ANDROID_VR") {
                client["androidSdkVersion"] = JsonPrimitive(32); client["osName"] = JsonPrimitive("Android"); client["osVersion"] = JsonPrimitive("12L")
            }
            val body = JsonObject(mapOf("videoId" to JsonPrimitive(videoId), "contentCheckOk" to JsonPrimitive(true), "racyCheckOk" to JsonPrimitive(true),
                "context" to JsonObject(mapOf("client" to JsonObject(client))))).toString()
            val root = json.parseToJsonElement(request("https://www.youtube.com/youtubei/v1/player?key=$key", body, profile.agent,
                mapOf("X-YouTube-Client-Name" to profile.id, "X-YouTube-Client-Version" to profile.version) + (visitor?.let { mapOf("X-Goog-Visitor-Id" to it) } ?: emptyMap()))).jsonObject
            last = root["playabilityStatus"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull ?: "UNKNOWN"
            if (last != "OK") continue
            val formats = root["streamingData"]?.jsonObject?.get("adaptiveFormats") as? JsonArray ?: continue
            return selectFormat(formats.mapNotNull { format(it.jsonObject) })
                ?: error("Brak bezpośredniego audio YouTube")
        }
        error("YouTube playability: $last")
    }

    private fun selectFormat(formats: List<AudioStreamSource>): AudioStreamSource? {
        if (formats.isEmpty()) return null
        val settings = settingsStore.settings.value
        val preferred = formats.filter { stream ->
            when (settings.codec) {
                CodecPreference.AUTO -> true
                CodecPreference.AAC -> stream.codec.contains("mp4a", true) || stream.codec.contains("aac", true)
                CodecPreference.OPUS -> stream.codec.contains("opus", true)
            }
        }.ifEmpty { formats }
        return when (settings.quality) {
            StreamQuality.DATA_SAVER -> preferred.minByOrNull { kotlin.math.abs(it.bitrate - 64_000) }
            StreamQuality.STANDARD -> preferred.minByOrNull { kotlin.math.abs(it.bitrate - 128_000) }
            StreamQuality.BEST -> preferred.maxByOrNull(AudioStreamSource::bitrate)
            StreamQuality.AUTO -> preferred.minByOrNull { kotlin.math.abs(it.bitrate - 128_000) }
        }
    }

    private fun format(item: JsonObject): AudioStreamSource? {
        val mime = item["mimeType"]?.jsonPrimitive?.contentOrNull ?: return null
        if (!mime.startsWith("audio/")) return null
        val url = item["url"]?.jsonPrimitive?.contentOrNull ?: return null
        return AudioStreamSource(SecretValue.of(url), mime, mime.substringAfter("audio/").substringBefore(';'),
            Regex("codecs=\"([^\"]+)\"").find(mime)?.groupValues?.get(1).orEmpty(), item["bitrate"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            item["contentLength"]?.jsonPrimitive?.content?.toLongOrNull(), Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1_000))
    }

    private suspend fun request(url: String, body: String? = null, agent: String = USER_AGENT, headers: Map<String, String> = emptyMap(), range: Boolean = false): String = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000; connection.readTimeout = 25_000; connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", agent); headers.forEach(connection::setRequestProperty)
            if (range) connection.setRequestProperty("Range", "bytes=0-4095")
            if (body != null) { connection.requestMethod = "POST"; connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("Origin", "https://www.youtube.com"); connection.outputStream.use { it.write(body.toByteArray()) } }
            val status = connection.responseCode; val response = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            require(status in 200..299) { "YouTube HTTP $status" }; response
        } finally { connection.disconnect() }
    }

    private fun text(value: JsonObject?): String? = value?.get("simpleText")?.jsonPrimitive?.contentOrNull ?: (value?.get("runs") as? JsonArray)?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
    private fun duration(value: String): Long? = value.split(':').mapNotNull(String::toLongOrNull).takeIf(List<Long>::isNotEmpty)?.fold(0L) { total, part -> total * 60 + part }?.times(1_000)
    private fun normalize(value: String) = value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    private fun extractObjects(source: String, marker: String): List<String> { val out=mutableListOf<String>(); var from=0; while(out.size<40){val m=source.indexOf(marker,from);if(m<0)break;val start=source.indexOf('{',m+marker.length);if(start<0)break;var depth=0;var quoted=false;var escaped=false;var end=-1;for(i in start until source.length){val c=source[i];if(quoted){if(escaped)escaped=false else if(c=='\\')escaped=true else if(c=='\"')quoted=false}else if(c=='\"')quoted=true else if(c=='{')depth++ else if(c=='}'&&--depth==0){end=i+1;break}};if(end<0)break;out+=source.substring(start,end);from=end};return out }
    private data class Profile(val name: String, val version: String, val id: String, val agent: String)
    companion object { private const val USER_AGENT="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/128.0 Safari/537.36"; private const val VR_AGENT="com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L) gzip" }
}
