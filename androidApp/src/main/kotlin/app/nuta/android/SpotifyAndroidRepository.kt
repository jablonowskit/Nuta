package app.nuta.android

import app.nuta.core.logging.NutaLogger
import app.nuta.core.models.Playlist
import app.nuta.core.models.Artist
import app.nuta.core.models.SearchResult
import app.nuta.core.models.Track
import app.nuta.domain.SpotifyRepository
import app.nuta.spotify.SpotifyWebToken
import android.content.SharedPreferences
import android.util.Base64
import java.net.HttpURLConnection
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

/** Android transport for the same private Spotify Web endpoints used on desktop. */
class SpotifyAndroidRepository(
    private val token: SpotifyWebToken,
    private val logger: NutaLogger,
    private val cache: SharedPreferences,
) : SpotifyRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getPlaylists(): List<Playlist> {
        val root = query("home", HOME_HASH, JsonObject(mapOf(
            "homeEndUserIntegration" to JsonPrimitive("INTEGRATION_WEB_PLAYER"),
            "timeZone" to JsonPrimitive("Europe/Warsaw"),
            "sp_t" to JsonPrimitive(""), "facet" to JsonPrimitive(""),
            "sectionItemsLimit" to JsonPrimitive(10),
            "includeEpisodeContentRatingsV2" to JsonPrimitive(false),
        )))
        return collectPlaylists(root).distinctBy(Playlist::id).take(30)
    }

    override suspend fun getSavedPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        val cached = readPlaylistCache()
        if (cached != null) return@withContext cached
        check(token.expiresAtMs > System.currentTimeMillis() + 30_000) { "Sesja Spotify wygasła. Zaloguj się ponownie." }
        val connection = URL("https://api.spotify.com/v1/me/playlists?limit=50").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            token.value.use { connection.setRequestProperty("Authorization", "Bearer $it") }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(status in 200..299) { "Spotify playlists HTTP $status: ${response.take(120)}" }
            val items = json.parseToJsonElement(response).jsonObject["items"] as? JsonArray ?: return@withContext emptyList()
            val playlists = items.mapNotNull { item ->
                val obj = item.jsonObject
                val id = obj["id"]?.asText() ?: return@mapNotNull null
                Playlist(id, obj["name"]?.asText() ?: "Playlist", obj["description"]?.asText().orEmpty(), emptyList(), (obj["images"] as? JsonArray)?.firstOrNull()?.asObject()?.get("url")?.asText())
            }
            writePlaylistCache(playlists)
            playlists
        } finally { connection.disconnect() }
    }

    private fun readPlaylistCache(): List<Playlist>? {
        val savedAt = cache.getLong("savedPlaylistsAt", 0L)
        if (savedAt == 0L || System.currentTimeMillis() - savedAt > 10 * 60 * 1000) return null
        return cache.getString("savedPlaylists", null)?.lineSequence()?.filter(String::isNotBlank)?.mapNotNull { row -> runCatching {
            val v = row.split("\u001e").map { String(Base64.decode(it, Base64.DEFAULT)) }
            Playlist(v[0], v[1], v[2], emptyList(), v[3].ifBlank { null })
        }.getOrNull() }?.toList()
    }

    private fun writePlaylistCache(playlists: List<Playlist>) {
        val value = playlists.joinToString("\n") { p -> listOf(p.id, p.name, p.description, p.imageUrl.orEmpty()).joinToString("\u001e") { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) } }
        cache.edit().putString("savedPlaylists", value).putLong("savedPlaylistsAt", System.currentTimeMillis()).apply()
    }

    override suspend fun getPlaylistTracks(playlistId: String): List<Track> {
        require(playlistId.matches(Regex("[A-Za-z0-9]+")))
        val root = query("fetchPlaylistContents", PLAYLIST_HASH, JsonObject(mapOf(
            "uri" to JsonPrimitive("spotify:playlist:$playlistId"),
            "offset" to JsonPrimitive(0), "limit" to JsonPrimitive(50),
            "includeEpisodeContentRatingsV2" to JsonPrimitive(false),
        )))
        return collectTracks(root).distinctBy(Track::id).take(50)
    }

    override suspend fun getLikedTracks(): List<Track> {
        val result = mutableListOf<Track>()
        var offset = 0
        var hasNext: Boolean
        do {
            val root = query("fetchLibraryTracks", LIBRARY_HASH, JsonObject(mapOf(
                "offset" to JsonPrimitive(offset), "limit" to JsonPrimitive(50),
            ))).jsonObject
            val page = root["data"]?.asObject()?.get("me")?.asObject()?.get("library")?.asObject()?.get("tracks")?.asObject() ?: break
            val items = page["items"] as? JsonArray ?: break
            result += items.mapNotNull { item ->
                val wrapper = item.asObject()?.get("track")?.asObject() ?: return@mapNotNull null
                val uri = wrapper["_uri"]?.asText() ?: return@mapNotNull null
                mapTrack(wrapper["data"]?.asObject() ?: return@mapNotNull null, uri)
            }
            offset += items.size
            val total = page["totalCount"]?.asText()?.toIntOrNull() ?: offset
            hasNext = items.isNotEmpty() && offset < total && result.size < 500
        } while (hasNext)
        return result.distinctBy(Track::id).also(::writeLikedCache)
    }

    override suspend fun getCachedLikedTracks(): List<Track> =
        cache.getString("likedTracks", "").orEmpty().lineSequence().filter(String::isNotBlank).mapNotNull { row -> runCatching {
            val v = row.split("\u001e").map { String(Base64.decode(it, Base64.DEFAULT)) }
            Track(v[0], v[1], v[2].split("\u001f"), v[3], v[4].toLong(), v[5].ifBlank { null })
        }.getOrNull() }.toList()

    private fun writeLikedCache(tracks: List<Track>) {
        val value = tracks.joinToString("\n") { t -> listOf(t.id, t.title, t.artists.joinToString("\u001f"), t.album, t.durationMs.toString(), t.imageUrl.orEmpty()).joinToString("\u001e") { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) } }
        cache.edit().putString("likedTracks", value).putLong("likedTracksAt", System.currentTimeMillis()).apply()
    }

    override suspend fun isTrackLiked(trackId: String): Boolean {
        val response = libraryRequest("GET", "contains", trackId)
        return (response as? JsonArray)?.firstOrNull()?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
    }

    override suspend fun setTrackLiked(trackId: String, liked: Boolean) {
        libraryRequest(if (liked) "PUT" else "DELETE", null, trackId)
    }

    override suspend fun search(query: String): SearchResult {
        if (query.isBlank()) return SearchResult(emptyList(), emptyList())
        val root = query("searchTracks", SEARCH_HASH, JsonObject(mapOf(
            "searchTerm" to JsonPrimitive(query), "offset" to JsonPrimitive(0), "limit" to JsonPrimitive(50),
            "numberOfTopResults" to JsonPrimitive(50), "includeAudiobooks" to JsonPrimitive(false),
            "includeArtistHasConcertsField" to JsonPrimitive(false), "includePreReleases" to JsonPrimitive(false),
            "includeLocalConcertsField" to JsonPrimitive(false), "includeAuthors" to JsonPrimitive(false),
        ))).jsonObject
        val searchRoot = root["data"]?.asObject()?.get("searchV2") ?: return SearchResult(emptyList(), emptyList())
        val items = searchRoot.asObject()?.get("tracksV2")?.asObject()?.get("items") as? JsonArray
        val tracks = items.orEmpty().mapNotNull { element ->
            val item = element.asObject()?.get("item")?.asObject()?.get("data")?.asObject() ?: return@mapNotNull null
            val uri = item["uri"]?.asText() ?: return@mapNotNull null
            mapTrack(item, uri)
        }.distinctBy(Track::id).take(50)
        logger.info("SpotifyAndroid", "search_completed", "Zakończono wyszukiwanie Spotify", fields = mapOf("count" to tracks.size.toString()))
        val artists = (collectArtists(root) + tracks.flatMap { track ->
            track.artists.map { name -> Artist(name.hashCode().toString(), name) }
        }).distinctBy(Artist::id).take(30)
        // collectPlaylists skanuje tę samą odpowiedź searchTracks (już uwierzytelnioną tym samym
        // tokenem pathfinder) — osobne zapytanie do publicznego REST /v1/search wymagało tokenu
        // z innego flow OAuth (Accounts, nie web-player) i zawsze kończyło się błędem autoryzacji,
        // po cichu połykanym jako pusta lista. Wzorem Spotube: jedno zapytanie, jeden token.
        val playlists = collectPlaylists(root).distinctBy(Playlist::id).take(30)
        return SearchResult(tracks, playlists, artists)
    }

    override suspend fun getTrackRadio(seed: Track, limit: Int): List<Track> {
        val candidates = (seed.artists + seed.album + seed.title).filter(String::isNotBlank)
            .flatMap { search(it).tracks }
            .distinctBy(Track::id).filterNot { it.id == seed.id }.take(limit)
        require(candidates.isNotEmpty()) { "Spotify nie zwrócił podobnych utworów" }
        return candidates
    }

    private suspend fun query(operation: String, hash: String, variables: JsonObject): JsonElement {
        check(token.expiresAtMs > System.currentTimeMillis() + 30_000) { "Sesja Spotify wygasła. Zaloguj się ponownie." }
        val body = JsonObject(mapOf(
            "variables" to variables,
            "operationName" to JsonPrimitive(operation),
            "extensions" to JsonObject(mapOf("persistedQuery" to JsonObject(mapOf(
                "version" to JsonPrimitive(1), "sha256Hash" to JsonPrimitive(hash),
            )))),
        )).toString()
        return withContext(Dispatchers.IO) {
            val connection = URL("https://api-partner.spotify.com/pathfinder/v2/query").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.doOutput = true
                connection.setRequestProperty("User-Agent", BROWSER_USER_AGENT)
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("App-Platform", "WebPlayer")
                connection.setRequestProperty("Origin", "https://open.spotify.com")
                connection.setRequestProperty("Referer", "https://open.spotify.com/")
                token.value.use { connection.setRequestProperty("Authorization", "Bearer $it") }
                connection.outputStream.use { it.write(body.toByteArray()) }
                val status = connection.responseCode
                val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    logger.warn(
                        "SpotifyAndroid",
                        "graphql_rejected",
                        "Spotify odrzuciło zapytanie GraphQL",
                        fields = mapOf("statusCode" to status.toString(), "response" to response.take(300)),
                    )
                    error("Spotify GraphQL HTTP $status: ${response.take(120)}")
                }
                json.parseToJsonElement(response)
            } finally {
                connection.disconnect()
            }
        }
    }

    private suspend fun libraryRequest(method: String, action: String?, trackId: String): JsonElement? {
        require(trackId.matches(Regex("[A-Za-z0-9]+"))) { "Nieprawidłowy identyfikator utworu" }
        check(token.expiresAtMs > System.currentTimeMillis() + 30_000) { "Sesja Spotify wygasła. Zaloguj się ponownie." }
        val suffix = action?.let { "/$it" }.orEmpty()
        return withContext(Dispatchers.IO) {
            val connection = URL("https://api.spotify.com/v1/me/tracks$suffix?ids=$trackId").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Length", "0")
                token.value.use { connection.setRequestProperty("Authorization", "Bearer $it") }
                val status = connection.responseCode
                val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                check(status in 200..299) { "Spotify library HTTP $status: ${response.take(120)}" }
                response.takeIf(String::isNotBlank)?.let(json::parseToJsonElement)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun collectPlaylists(element: JsonElement): List<Playlist> = when (element) {
        is JsonArray -> element.flatMap(::collectPlaylists)
        is JsonObject -> {
            val uri = element["uri"]?.asText()
            val current = if (uri?.startsWith("spotify:playlist:") == true) {
                (element["name"]?.asText() ?: element["title"]?.asObject()?.get("transformedLabel")?.asText())
                    ?.let { Playlist(uri.substringAfterLast(':'), it, "Rekomendacja Spotify", emptyList(), spotifyImageUrl(element)) }
            } else null
            listOfNotNull(current) + element.values.flatMap(::collectPlaylists)
        }
        else -> emptyList()
    }

    private fun collectArtists(element: JsonElement): List<Artist> = when (element) {
        is JsonArray -> element.flatMap(::collectArtists)
        is JsonObject -> {
            val uri = element["uri"]?.asText()
            val artist = if (uri?.startsWith("spotify:artist:") == true) {
                element["name"]?.asText()?.let { Artist(uri.substringAfterLast(':'), it, spotifyImageUrl(element)) }
            } else null
            listOfNotNull(artist) + element.values.flatMap(::collectArtists)
        }
        else -> emptyList()
    }.distinctBy(Artist::id)

    private fun collectTracks(element: JsonElement): List<Track> = when (element) {
        is JsonArray -> element.flatMap(::collectTracks)
        is JsonObject -> {
            val uri = element["uri"]?.asText()
            listOfNotNull(if (uri?.startsWith("spotify:track:") == true) mapTrack(element, uri) else null) +
                element.values.flatMap(::collectTracks)
        }
        else -> emptyList()
    }

    private fun mapTrack(item: JsonObject, uri: String): Track? {
        val title = item["name"]?.asText() ?: return null
        val artists = (item["artists"]?.asObject()?.get("items") as? JsonArray).orEmpty().mapNotNull {
            it.asObject()?.get("profile")?.asObject()?.get("name")?.asText() ?: it.asObject()?.get("name")?.asText()
        }
        val duration = item["trackDuration"]?.asObject()?.get("totalMilliseconds")?.asText()?.toLongOrNull()
            ?: item["duration"]?.asObject()?.get("totalMilliseconds")?.asText()?.toLongOrNull()
            ?: item["durationMs"]?.asText()?.toLongOrNull() ?: 0L
        return Track(uri.substringAfterLast(':'), title, artists,
            item["albumOfTrack"]?.asObject()?.get("name")?.asText().orEmpty(), duration,
            spotifyImageUrl(item["albumOfTrack"] ?: item))
    }

    private fun spotifyImageUrl(element: JsonElement): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull?.takeIf { it.startsWith("https://") && ("scdn.co" in it || "spotifycdn" in it) }
        is JsonArray -> element.firstNotNullOfOrNull(::spotifyImageUrl)
        is JsonObject -> {
            val priority = listOf("sources", "images", "coverArt", "image")
            priority.firstNotNullOfOrNull { key -> element[key]?.let(::spotifyImageUrl) }
                ?: element.values.firstNotNullOfOrNull(::spotifyImageUrl)
        }
        else -> null
    }

    private fun JsonElement.asObject() = this as? JsonObject
    private fun JsonElement.asText() = (this as? JsonPrimitive)?.contentOrNull

    companion object {
        private const val SEARCH_HASH = "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428"
        private const val HOME_HASH = "76243c78b0e20ecdbe41b794dec8cbe73f75e585b0a7201b8d2e84578412847a"
        private const val PLAYLIST_HASH = "a65e12194ed5fc443a1cdebed5fabe33ca5b07b987185d63c72483867ad13cb4"
        private const val LIBRARY_HASH = "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240"
        private const val BROWSER_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/128.0 Safari/537.36"
    }
}
