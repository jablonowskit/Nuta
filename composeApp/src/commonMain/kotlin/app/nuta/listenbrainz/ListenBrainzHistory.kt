package app.nuta.listenbrainz

import app.nuta.core.models.Track
import app.nuta.net.httpGet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Jedno odsłuchanie z historii; [listenedAt] w sekundach UNIX (jak w API). */
data class HistoryEntry(val track: Track, val listenedAt: Long)

/** [oldestTs] to `max_ts` dla następnej strony; null = koniec historii. */
data class HistoryPage(val entries: List<HistoryEntry>, val oldestTs: Long?)

/**
 * Historia odtwarzania z `/1/user/<nazwa>/listens` — to, co scrobbler wysłał (i inne klienty
 * użytkownika). Kształt sprawdzony curlem 27.10.2026: `payload.listens[]` z `listened_at`,
 * `recording_msid` i `track_metadata` (+ `mbid_mapping`, gdy ListenBrainz dopasował nagranie).
 */
class ListenBrainzHistory {
    suspend fun page(user: String, maxTs: Long? = null, count: Int = PageSize): HistoryPage {
        val url = buildString {
            append("https://api.listenbrainz.org/1/user/${userPathSegment(user)}/listens?count=$count")
            if (maxTs != null) append("&max_ts=$maxTs")
        }
        return parse(httpGet(url), count)
    }

    companion object {
        const val PageSize = 50
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(body: String, requested: Int = PageSize): HistoryPage {
            if (body.isBlank()) return HistoryPage(emptyList(), null)
            val payload = runCatching { (json.parseToJsonElement(body) as? JsonObject)?.get("payload") as? JsonObject }
                .getOrNull() ?: return HistoryPage(emptyList(), null)
            val listens = payload["listens"] as? JsonArray ?: return HistoryPage(emptyList(), null)
            val entries = listens.mapNotNull { item ->
                val listen = item as? JsonObject ?: return@mapNotNull null
                val listenedAt = listen["listened_at"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val meta = listen["track_metadata"] as? JsonObject ?: return@mapNotNull null
                fun str(o: JsonObject?, name: String) = o?.get(name)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val title = str(meta, "track_name") ?: return@mapNotNull null
                val mapping = meta["mbid_mapping"] as? JsonObject
                val info = meta["additional_info"] as? JsonObject
                val recordingMbid = str(mapping, "recording_mbid")
                    ?: str(info, "recording_mbid")?.takeIf { ListenBrainzRepository.MbidRegex.matches(it) }
                val msid = str(listen, "recording_msid")
                val id = recordingMbid ?: msid?.let { ListenBrainzRepository.MsidIdPrefix + it } ?: "listen-$listenedAt"
                val cover = ListenBrainzRepository.coverArtUrl(str(mapping, "caa_release_mbid"), str(mapping, "caa_id"))
                    ?: str(mapping, "release_group_mbid")?.let { "https://coverartarchive.org/release-group/$it/front-250" }
                HistoryEntry(
                    Track(
                        id = id,
                        title = title,
                        artists = listOfNotNull(str(meta, "artist_name")),
                        album = str(meta, "release_name").orEmpty(),
                        durationMs = info?.get("duration_ms")?.jsonPrimitive?.longOrNull ?: 0L,
                        imageUrl = cover,
                        artistMbid = (mapping?.get("artist_mbids") as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull,
                    ),
                    listenedAt,
                )
            }
            // Krótsza strona niż prośba = doszliśmy do początku historii.
            val oldest = entries.minOfOrNull { it.listenedAt }?.takeIf { listens.size >= requested }
            return HistoryPage(entries, oldest)
        }
    }
}
