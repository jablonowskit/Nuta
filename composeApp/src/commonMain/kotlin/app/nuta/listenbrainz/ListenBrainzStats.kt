package app.nuta.listenbrainz

import app.nuta.core.models.Track
import app.nuta.net.httpGet
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Zakres czasu statystyk — wartości parametru `range` w API ListenBrainz. */
enum class StatsRange(val apiValue: String) { WEEK("week"), MONTH("month"), YEAR("year"), ALL_TIME("all_time") }

enum class StatsKind { RECORDINGS, ARTISTS, RELEASES }

/** Jedna pozycja topki. [track] jest ustawiony tylko dla utworów (da się go odtworzyć). */
data class StatsEntry(
    val title: String,
    val subtitle: String,
    val listenCount: Long,
    val imageUrl: String?,
    val mbid: String?,
    val track: Track? = null,
)

/**
 * Topki z `/1/stats/{sitewide|user/<nazwa>}/{recordings|artists|releases}` — to samo, co pokazuje
 * listenbrainz.org/statistics. Statystyki liczone są po stronie ListenBrainz okresowo (pole
 * `last_updated`), więc nie ma sensu odpytywać częściej niż przy wejściu na ekran.
 */
class ListenBrainzStats {
    /** [user] == null → statystyki ogólnoświatowe. Pusta lista = brak policzonych danych (HTTP 204). */
    suspend fun top(user: String?, kind: StatsKind, range: StatsRange, count: Int = 50): List<StatsEntry> {
        val scope = if (user == null) "sitewide" else "user/${URLEncoder.encode(user, "UTF-8")}"
        val path = when (kind) {
            StatsKind.RECORDINGS -> "recordings"
            StatsKind.ARTISTS -> "artists"
            StatsKind.RELEASES -> "releases"
        }
        return parse(httpGet("https://api.listenbrainz.org/1/stats/$scope/$path?range=${range.apiValue}&count=$count"), kind)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(body: String, kind: StatsKind): List<StatsEntry> {
            if (body.isBlank()) return emptyList()
            val payload = runCatching { (json.parseToJsonElement(body) as? JsonObject)?.get("payload") as? JsonObject }
                .getOrNull() ?: return emptyList()
            val key = when (kind) {
                StatsKind.RECORDINGS -> "recordings"
                StatsKind.ARTISTS -> "artists"
                StatsKind.RELEASES -> "releases"
            }
            return (payload[key] as? JsonArray).orEmpty().mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                fun str(name: String) = o[name]?.jsonPrimitive?.contentOrNull
                val count = o["listen_count"]?.jsonPrimitive?.longOrNull ?: 0L
                val artist = str("artist_name").orEmpty()
                val cover = ListenBrainzRepository.coverArtUrl(str("caa_release_mbid"), str("caa_id"))
                when (kind) {
                    StatsKind.ARTISTS -> StatsEntry(artist.ifBlank { return@mapNotNull null }, "", count, null, str("artist_mbid"))
                    StatsKind.RELEASES -> StatsEntry(str("release_name") ?: return@mapNotNull null, artist, count, cover, str("release_mbid"))
                    StatsKind.RECORDINGS -> {
                        val title = str("track_name") ?: return@mapNotNull null
                        val mbid = str("recording_mbid")
                        StatsEntry(
                            title, artist, count, cover, mbid,
                            track = Track(
                                // Bez MBID nagrania (niezmapowane odsłuchy) id musi być i tak unikalne.
                                id = mbid ?: "stats-$artist-$title",
                                title = title,
                                artists = listOfNotNull(artist.takeIf(String::isNotBlank)),
                                album = str("release_name").orEmpty(),
                                durationMs = 0L,
                                imageUrl = cover,
                                artistMbid = (o["artist_mbids"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull,
                            ),
                        )
                    }
                }
            }
        }
    }
}
