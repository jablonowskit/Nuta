package app.nuta.player

import app.nuta.core.models.PlayerState
import app.nuta.core.models.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Persystencja kolejki desktop (jak Android SharedPreferences playback-queue).
 * Bez auto-play po restarcie — tylko kolejka, indeks i pozycja.
 */
class FilePlaybackQueueStore(
    private val path: Path = defaultPath(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): PlayerState = runCatching {
        if (!Files.isRegularFile(path)) return PlayerState()
        val root = json.parseToJsonElement(Files.readString(path)).jsonObject
        val tracks = root["tracks"]?.jsonArray?.mapNotNull { element ->
            val o = element.jsonObject
            Track(
                id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                artists = o["artists"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                album = o["album"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                durationMs = o["durationMs"]?.jsonPrimitive?.longOrNull ?: 0L,
                imageUrl = o["imageUrl"]?.jsonPrimitive?.contentOrNull,
                artistMbid = o["artistMbid"]?.jsonPrimitive?.contentOrNull,
            )
        }.orEmpty()
        val index = (root["index"]?.jsonPrimitive?.intOrNull ?: -1).coerceIn(-1, tracks.lastIndex)
        val positionMs = (root["positionMs"]?.jsonPrimitive?.longOrNull ?: 0L).coerceAtLeast(0)
        PlayerState(queue = tracks, currentIndex = index, positionMs = if (index >= 0) positionMs else 0L)
    }.getOrDefault(PlayerState())

    fun save(state: PlayerState) {
        Files.createDirectories(path.parent)
        val body = buildJsonObject {
            put("index", state.currentIndex)
            put("positionMs", state.positionMs.coerceAtLeast(0))
            put("tracks", buildJsonArray {
                state.queue.forEach { t ->
                    add(buildJsonObject {
                        put("id", t.id)
                        put("title", t.title)
                        put("artists", buildJsonArray { t.artists.forEach { add(JsonPrimitive(it)) } })
                        put("album", t.album)
                        put("durationMs", t.durationMs)
                        t.imageUrl?.let { put("imageUrl", it) }
                        t.artistMbid?.let { put("artistMbid", it) }
                    })
                }
            })
        }.toString()
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(tmp, body, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        fun defaultPath(): Path {
            val session = System.getenv("NUTA_SESSION_DIR")
            return if (!session.isNullOrBlank()) Path.of(session).parent.resolve("playback-queue.json")
            else Path.of(System.getProperty("user.home"), ".local", "share", "nuta", "playback-queue.json")
        }
    }
}
