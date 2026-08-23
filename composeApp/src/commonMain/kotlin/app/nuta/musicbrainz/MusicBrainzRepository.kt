package app.nuta.musicbrainz

import app.nuta.core.logging.NutaLogger
import app.nuta.core.models.SearchResult
import app.nuta.core.models.Track
import app.nuta.net.httpGet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Wyszukiwanie katalogu w trybie ListenBrainz — ListenBrainz samo nie ma wyszukiwania,
 * korzysta z siostrzanej bazy metadanych MusicBrainz. Kształt JSON (`artist-credit[].artist.name`)
 * różni się od batch-lookupu ListenBrainz (`artist.artists[].name`), więc ma własny, osobny
 * parser — zweryfikowane curlem 2026-08-23.
 */
class MusicBrainzRepository(private val logger: NutaLogger) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: String): SearchResult {
        if (query.isBlank()) return SearchResult(emptyList(), emptyList())
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val body = httpGet(
            "https://musicbrainz.org/ws/2/recording/?query=$encoded&fmt=json&limit=20",
            headers = mapOf("User-Agent" to UserAgent),
        )
        val recordings = (json.parseToJsonElement(body).jsonObject["recordings"] as? JsonArray).orEmpty()
        val tracks = recordings.mapNotNull { element ->
            val obj = element.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val artistCredit = (obj["artist-credit"] as? JsonArray).orEmpty()
            val artists = artistCredit.mapNotNull { it.jsonObject["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull }
            val artistMbid = artistCredit.firstOrNull()?.jsonObject?.get("artist")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
            Track(
                id = id,
                title = title,
                artists = artists,
                album = "",
                durationMs = obj["length"]?.jsonPrimitive?.longOrNull ?: 0L,
                artistMbid = artistMbid,
            )
        }
        logger.info("MusicBrainz", "search_completed", "Zakończono wyszukiwanie MusicBrainz", fields = mapOf("results" to tracks.size.toString()))
        return SearchResult(tracks = tracks, playlists = emptyList(), artists = emptyList())
    }

    private companion object {
        const val UserAgent = "Nuta/1.0 ( https://github.com/jablonowskit/Nuta )"
    }
}
