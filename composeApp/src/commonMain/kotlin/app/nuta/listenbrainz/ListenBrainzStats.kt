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

/**
 * Zakres czasu statystyk — wartości parametru `range` w API ListenBrainz. [activityApiValue] dla
 * wykresu aktywności: `this_*` sięga do dziś, a gołe `week`/`month` kończą się na poprzednim pełnym
 * okresie (curl 27.10.2026: `month` dawał sierpień–wrzesień, `this_month` wrzesień–październik).
 * ListenBrainz zwraca w obu przypadkach dwa okresy — poprzedni i bieżący — do porównania.
 */
enum class StatsRange(val apiValue: String, val activityApiValue: String) {
    WEEK("week", "this_week"), MONTH("month", "this_month"), YEAR("year", "this_year"), ALL_TIME("all_time", "all_time"),
}

/** Kubełek wykresu aktywności: dzień, miesiąc albo rok, zależnie od zakresu. */
data class ActivityBucket(val label: String, val fromTs: Long, val count: Long)

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
 * Nazwa użytkownika jako segment ścieżki URL. URLEncoder koduje formularze (spacja → `+`),
 * a w ścieżce `+` to dosłowny plus — stąd zamiana na `%20`.
 */
internal fun userPathSegment(user: String): String = URLEncoder.encode(user.trim(), "UTF-8").replace("+", "%20")

/**
 * Topki z `/1/stats/{sitewide|user/<nazwa>}/{recordings|artists|releases}` — to samo, co pokazuje
 * listenbrainz.org/statistics. Statystyki liczone są po stronie ListenBrainz okresowo (pole
 * `last_updated`), więc nie ma sensu odpytywać częściej niż przy wejściu na ekran.
 */
class ListenBrainzStats {
    /** [user] == null → statystyki ogólnoświatowe. Pusta lista = brak policzonych danych (HTTP 204). */
    suspend fun top(user: String?, kind: StatsKind, range: StatsRange, count: Int = 50): List<StatsEntry> {
        val scope = if (user == null) "sitewide" else "user/${userPathSegment(user)}"
        val path = when (kind) {
            StatsKind.RECORDINGS -> "recordings"
            StatsKind.ARTISTS -> "artists"
            StatsKind.RELEASES -> "releases"
        }
        return parse(httpGet("https://api.listenbrainz.org/1/stats/$scope/$path?range=${range.apiValue}&count=$count"), kind)
    }

    /** Liczba odsłuchań w czasie z `/1/stats/user/<nazwa>/listening-activity`. */
    suspend fun activity(user: String, range: StatsRange): List<ActivityBucket> =
        parseActivity(httpGet("https://api.listenbrainz.org/1/stats/user/${userPathSegment(user)}/listening-activity?range=${range.activityApiValue}"))

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Kubełki w kolejności czasu. Wiodące zera odcinamy: `all_time` zaczyna od 2002 roku
         * i wykres byłby w większości pusty.
         */
        fun parseActivity(body: String): List<ActivityBucket> {
            if (body.isBlank()) return emptyList()
            val payload = runCatching { (json.parseToJsonElement(body) as? JsonObject)?.get("payload") as? JsonObject }
                .getOrNull() ?: return emptyList()
            val items = payload["listening_activity"] as? JsonArray ?: return emptyList()
            return items.mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                ActivityBucket(
                    label = o["time_range"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    fromTs = o["from_ts"]?.jsonPrimitive?.longOrNull ?: 0L,
                    count = o["listen_count"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            }.sortedBy { it.fromTs }.dropWhile { it.count == 0L }
        }

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
