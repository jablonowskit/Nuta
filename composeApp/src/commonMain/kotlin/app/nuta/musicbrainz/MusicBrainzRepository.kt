package app.nuta.musicbrainz

import app.nuta.core.logging.NutaLogger
import app.nuta.core.models.SearchResult
import app.nuta.core.models.Track
import app.nuta.net.httpGet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Wyszukiwanie katalogu w trybie ListenBrainz — ListenBrainz samo nie ma wyszukiwania,
 * korzysta z siostrzanej bazy metadanych MusicBrainz. Kształt JSON (`artist-credit[].artist.name`)
 * różni się od batch-lookupu ListenBrainz (`artist.artists[].name`), więc ma własny, osobny
 * parser — zweryfikowane curlem 2026-08-23.
 */
class MusicBrainzRepository(private val logger: NutaLogger) {
    suspend fun search(query: String): SearchResult {
        if (query.isBlank()) return SearchResult(emptyList(), emptyList())
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val body = httpGet(
            "https://musicbrainz.org/ws/2/recording/?query=$encoded&fmt=json&limit=20",
            headers = mapOf("User-Agent" to UserAgent),
        )
        val tracks = parseRecordings(body)
        logger.info("MusicBrainz", "search_completed", "Zakończono wyszukiwanie MusicBrainz", fields = mapOf("results" to tracks.size.toString()))
        return SearchResult(tracks = tracks, playlists = emptyList(), artists = emptyList())
    }

    internal companion object {
        const val UserAgent = "Nuta/1.0 ( https://github.com/jablonowskit/Nuta )"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parsowanie odpowiedzi `ws/2/recording/` wydzielone z [search], żeby dało się je
         * sprawdzić testami na prawdziwych odpowiedziach API bez sieci — tu wychodziły błędy,
         * których kompilator nie widzi (brakujące pola, `length: null`, jawne JSON-owe null-e).
         *
         * Pusta odpowiedź i niekompletne wpisy dają pustą listę / pominięty wpis zamiast
         * wyjątku: awaria wyszukiwania nie może wywalić apki.
         */
        fun parseRecordings(body: String): List<Track> {
            if (body.isBlank()) return emptyList()
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
            val recordings = (root["recordings"] as? JsonArray).orEmpty()
            return recordings.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val artistCredit = (obj["artist-credit"] as? JsonArray).orEmpty()
                val artists = artistCredit.mapNotNull {
                    (it as? JsonObject)?.get("artist")?.let { a -> a as? JsonObject }?.get("name")?.jsonPrimitive?.contentOrNull
                }
                val artistMbid = (artistCredit.firstOrNull() as? JsonObject)
                    ?.get("artist")?.let { it as? JsonObject }
                    ?.get("id")?.jsonPrimitive?.contentOrNull
                Track(
                    id = id,
                    title = title,
                    artists = artists,
                    album = "",
                    // `length` bywa jawnym null-em — wtedy 0, a odtwarzacz nie klampuje pozycji
                    // do zera (patrz seekTo w Media3AudioPlayer/MpvAudioPlayer).
                    durationMs = obj["length"]?.jsonPrimitive?.longOrNull ?: 0L,
                    artistMbid = artistMbid,
                )
            }
        }
    }
}
