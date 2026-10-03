package app.nuta.data

import app.nuta.core.models.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class FileLikedTracksCache(
    private val path: Path = defaultPath(),
) : LikedTracksCache {
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): List<Track> = runCatching {
        if (!Files.isRegularFile(path)) return emptyList()
        val root = json.parseToJsonElement(Files.readString(path)).jsonObject
        val tracks = root["tracks"]?.jsonArray ?: return emptyList()
        tracks.mapNotNull { element ->
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
        }
    }.getOrDefault(emptyList())

    override fun save(tracks: List<Track>) {
        Files.createDirectories(path.parent)
        val body = buildJsonObject {
            put("tracks", buildJsonArray {
                tracks.forEach { t ->
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

    override fun clear() {
        runCatching { Files.deleteIfExists(path) }
    }

    companion object {
        fun defaultPath(): Path {
            val session = System.getenv("NUTA_SESSION_DIR")
            return if (!session.isNullOrBlank()) Path.of(session).parent.resolve("liked-tracks.json")
            else Path.of(System.getProperty("user.home"), ".local", "share", "nuta", "liked-tracks.json")
        }
    }
}
