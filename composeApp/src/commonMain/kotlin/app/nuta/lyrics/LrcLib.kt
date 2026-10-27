package app.nuta.lyrics

import app.nuta.core.models.Track
import app.nuta.musicbrainz.MusicBrainzRepository
import app.nuta.net.httpGet
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class LyricLine(val timeMs: Long, val text: String)

/** [synced] ma pierwszeństwo przy wyświetlaniu; [plain] to zapas, gdy LRCLIB nie ma znaczników czasu. */
data class Lyrics(val plain: String?, val synced: List<LyricLine>?, val instrumental: Boolean)

/**
 * Teksty z LRCLIB (lrclib.net, darmowe, bez klucza). Zweryfikowane curlem 27.10.2026:
 * `/api/get` zwraca `plainLyrics`, `syncedLyrics` w formacie LRC (`[mm:ss.xx] tekst`)
 * i `instrumental`; brak tekstu to HTTP 404 `TrackNotFound`. Bez `duration` serwer potrafi
 * odpowiedzieć 503, więc wysyłamy ją zawsze, gdy jest znana.
 */
class LrcLibClient {
    /** null = LRCLIB nie zna utworu. Wyjątek = błąd sieci (UI może zaproponować ponowienie). */
    suspend fun lyrics(track: Track): Lyrics? {
        cacheGet(track.id)?.let { return it.value }
        val url = buildString {
            append("https://lrclib.net/api/get?artist_name=").append(encode(track.artists.firstOrNull().orEmpty()))
            append("&track_name=").append(encode(track.title))
            if (track.album.isNotBlank()) append("&album_name=").append(encode(track.album))
            if (track.durationMs > 0) append("&duration=").append(track.durationMs / 1000)
        }
        val result = try {
            parse(httpGet(url, headers = mapOf("User-Agent" to MusicBrainzRepository.UserAgent)))
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            // httpGet rzuca „HTTP <kod>: …” dla każdego statusu poza 2xx
            if (error.message?.startsWith("HTTP 404") == true) null else throw error
        }
        cachePut(track.id, result)
        return result
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    /** Opakowanie, żeby zapamiętać też „brak tekstu” (null) i nie pytać o niego ponownie. */
    private class Cached(val value: Lyrics?)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val CacheSize = 50
        // Wspólny dla procesu — AppContainer bywa przebudowywany (np. odświeżenie tokenu Spotify).
        private val cache = LinkedHashMap<String, Cached>()

        private fun cacheGet(key: String): Cached? = synchronized(cache) { cache[key] }

        private fun cachePut(key: String, value: Lyrics?) = synchronized(cache) {
            cache.remove(key)
            cache[key] = Cached(value)
            while (cache.size > CacheSize) cache.remove(cache.keys.first())
        }

        /** Odpowiedź `/api/get`. null, gdy treść nie jest obiektem albo nie ma żadnego tekstu. */
        fun parse(body: String): Lyrics? {
            val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            val instrumental = obj["instrumental"]?.jsonPrimitive?.booleanOrNull == true
            val plain = obj["plainLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val synced = obj["syncedLyrics"]?.jsonPrimitive?.contentOrNull?.let(::parseLrc)?.takeIf { it.isNotEmpty() }
            if (!instrumental && plain == null && synced == null) return null
            return Lyrics(plain, synced, instrumental)
        }

        private val TimeTag = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

        /**
         * Linie LRC posortowane po czasie. Jedna linia może mieć kilka znaczników (powtarzany
         * refren: `[00:12.00][01:30.00]tekst`). Linie bez tekstu (przerwy instrumentalne) zostają —
         * pusta linia podświetlona w trakcie solówki jest lepsza niż wisząca poprzednia.
         */
        fun parseLrc(text: String): List<LyricLine> = text.lineSequence().flatMap { line ->
            val tags = TimeTag.findAll(line).toList()
            if (tags.isEmpty() || tags.first().range.first != 0) return@flatMap emptySequence()
            val lyric = line.substring(tags.last().range.last + 1).trim()
            tags.asSequence().map { tag ->
                val (min, sec, frac) = tag.destructured
                val fracMs = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.toLong()
                }
                LyricLine(min.toLong() * 60_000 + sec.toLong() * 1_000 + fracMs, lyric)
            }
        }.sortedBy { it.timeMs }.toList()
    }
}
