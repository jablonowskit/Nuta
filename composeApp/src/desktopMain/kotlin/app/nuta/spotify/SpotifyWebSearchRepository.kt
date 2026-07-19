package app.nuta.spotify

import app.nuta.core.logging.NutaLogger
import app.nuta.core.models.Playlist
import app.nuta.core.models.Artist
import app.nuta.core.models.SearchResult
import app.nuta.core.models.Track
import app.nuta.core.security.SecretValue
import app.nuta.domain.SpotifyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

class SpotifyWebSearchRepository(
    initialToken: SpotifyWebToken,
    private val logger: NutaLogger,
) : SpotifyRepository {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val tokenMutex = Mutex()
    private var cachedToken: SpotifyWebToken? = initialToken

    override suspend fun getPlaylists(): List<Playlist> {
        val operationId = "spotify-home-${System.currentTimeMillis()}"
        logger.info("SpotifyHome", "home_started", "Pobieranie rekomendacji strony głównej", operationId)
        return try {
            val token = validToken()
            val body = JsonObject(mapOf(
                "variables" to JsonObject(mapOf(
                    "homeEndUserIntegration" to JsonPrimitive("INTEGRATION_WEB_PLAYER"),
                    "timeZone" to JsonPrimitive("Europe/Warsaw"),
                    "sp_t" to JsonPrimitive(""),
                    "facet" to JsonPrimitive(""),
                    "sectionItemsLimit" to JsonPrimitive(10),
                    "includeEpisodeContentRatingsV2" to JsonPrimitive(false),
                )),
                "operationName" to JsonPrimitive("home"),
                "extensions" to JsonObject(mapOf("persistedQuery" to JsonObject(mapOf(
                    "version" to JsonPrimitive(1), "sha256Hash" to JsonPrimitive(HomeHash),
                )))),
            )).toString()
            val root = postJson("https://api-partner.spotify.com/pathfinder/v2/query", body, token)
            val playlists = collectPlaylists(root).distinctBy(Playlist::id).take(30)
            logger.info("SpotifyHome", "home_completed", "Pobrano rekomendowane playlisty", operationId, mapOf("count" to playlists.size.toString()))
            playlists
        } catch (error: Throwable) {
            logger.error("SpotifyHome", "home_failed", "Nie udało się pobrać rekomendacji Spotify", operationId, throwable = error)
            throw error
        }
    }

    override suspend fun getSavedPlaylists(): List<Playlist> {
        val root = getJson("https://api.spotify.com/v1/me/playlists?limit=50", validToken())
        return (root.jsonObject["items"] as? JsonArray).orEmpty().mapNotNull { item ->
            val obj = item.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val image = (obj["images"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            Playlist(id, obj["name"]?.jsonPrimitive?.contentOrNull ?: "Playlist", obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(), emptyList(), image)
        }
    }

    override suspend fun getPlaylistTracks(playlistId: String): List<Track> {
        require(playlistId.matches(Regex("[A-Za-z0-9]+"))) { "Nieprawidłowy identyfikator playlisty" }
        val token = validToken()
        val body = JsonObject(mapOf(
            "variables" to JsonObject(mapOf(
                "uri" to JsonPrimitive("spotify:playlist:$playlistId"),
                "offset" to JsonPrimitive(0),
                "limit" to JsonPrimitive(50),
                "includeEpisodeContentRatingsV2" to JsonPrimitive(false),
            )),
            "operationName" to JsonPrimitive("fetchPlaylistContents"),
            "extensions" to JsonObject(mapOf("persistedQuery" to JsonObject(mapOf(
                "version" to JsonPrimitive(1), "sha256Hash" to JsonPrimitive(PlaylistContentsHash),
            )))),
        )).toString()
        val root = postJson("https://api-partner.spotify.com/pathfinder/v2/query", body, token)
        val tracks = collectTracks(root).distinctBy(Track::id).take(50)
        logger.info("SpotifyPlaylist", "tracks_loaded", "Pobrano utwory playlisty przez GraphQL", fields = mapOf("count" to tracks.size.toString()))
        return tracks
    }

    override suspend fun getLikedTracks(): List<Track> {
        val operationId = "spotify-liked-${System.currentTimeMillis()}"
        logger.info("SpotifyLiked", "liked_started", "Pobieranie ulubionych utworów Spotify", operationId)
        val token = validToken()
        val tracks = mutableListOf<Track>()
        var offset = 0
        do {
            val body = JsonObject(mapOf(
                "variables" to JsonObject(mapOf("offset" to JsonPrimitive(offset), "limit" to JsonPrimitive(50))),
                "operationName" to JsonPrimitive("fetchLibraryTracks"),
                "extensions" to JsonObject(mapOf("persistedQuery" to JsonObject(mapOf(
                    "version" to JsonPrimitive(1), "sha256Hash" to JsonPrimitive(LibraryTracksHash),
                )))),
            )).toString()
            val root = postJson("https://api-partner.spotify.com/pathfinder/v2/query", body, token).jsonObject
            val page = root["data"]?.jsonObject?.get("me")?.jsonObject?.get("library")?.jsonObject?.get("tracks")?.jsonObject ?: break
            val items = page["items"] as? JsonArray ?: break
            tracks += items.mapNotNull(::mapLibraryTrack)
            offset += items.size
            val total = page["totalCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: offset
            val hasNext = offset < total && items.isNotEmpty()
        } while (hasNext && tracks.size < 500)
        val result = tracks.distinctBy(Track::id)
        logger.info("SpotifyLiked", "liked_completed", "Pobrano ulubione utwory Spotify", operationId, mapOf("count" to result.size.toString()))
        return result
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
        val operationId = "spotify-search-${System.currentTimeMillis()}"
        logger.debug("SpotifySearch", "search_started", "Rozpoczęto wyszukiwanie utworów", operationId, mapOf("queryLength" to query.length.toString()))
        return try {
            val token = validToken()
            val payload = searchPayload(query, token)
            val tracks = payload.tracks
            logger.info("SpotifySearch", "search_completed", "Zakończono wyszukiwanie utworów", operationId, mapOf("results" to tracks.size.toString()))
            val artists = (collectArtists(payload.root) + tracks.flatMap { track ->
                track.artists.map { name -> Artist(name.hashCode().toString(), name) }
            }).distinctBy(Artist::id).take(30)
            val playlists = (collectPlaylists(payload.root) + searchPlaylists(query, token)).distinctBy(Playlist::id).take(30)
            SearchResult(tracks, playlists, artists)
        } catch (error: Throwable) {
            logger.error("SpotifySearch", "search_failed", "Wyszukiwanie Spotify nie powiodło się", operationId, throwable = error)
            throw error
        }
    }

    override suspend fun getTrackRadio(seed: Track, limit: Int): List<Track> {
        require(limit in 1..50) { "Limit radia musi mieścić się w zakresie 1..50" }
        require(seed.id.matches(Regex("[A-Za-z0-9]+"))) { "Nieprawidłowy identyfikator utworu" }
        val operationId = "spotify-radio-${System.currentTimeMillis()}"
        logger.info("SpotifyRadio", "radio_started", "Tworzenie radia utworu", operationId, mapOf("limit" to limit.toString()))
        val token = validToken()
        val stationTracks = runCatching {
            val root = getJson("https://spclient.wg.spotify.com/context-resolve/v1/spotify:station:track:${seed.id}", token)
            collectTracks(root).distinctBy(Track::id).filterNot { it.id == seed.id }.take(limit)
        }.onFailure { error ->
            logger.warn("SpotifyRadio", "station_context_unavailable", "Kontekst stacji Spotify jest niedostępny; używany jest fallback wyszukiwania", operationId, mapOf("reason" to error.javaClass.simpleName))
        }.getOrDefault(emptyList())
        if (stationTracks.isNotEmpty()) {
            logger.info("SpotifyRadio", "radio_completed", "Pobrano radio utworu ze stacji Spotify", operationId, mapOf("count" to stationTracks.size.toString(), "source" to "station"))
            return stationTracks
        }
        val queries = buildList {
            seed.artists.take(3).forEach(::add)
            if (seed.album.isNotBlank()) add(seed.album)
            add(seed.title)
        }.distinct()
        val fallback = queries.flatMap { query ->
            runCatching { searchTracks(query, token) }.getOrElse { emptyList() }
        }.distinctBy(Track::id).filterNot { it.id == seed.id }.take(limit)
        require(fallback.isNotEmpty()) { "Spotify nie zwrócił utworów dla radia" }
        logger.info("SpotifyRadio", "radio_completed", "Utworzono radio na podstawie katalogu Spotify", operationId, mapOf("count" to fallback.size.toString(), "source" to "search_fallback"))
        return fallback
    }

    private suspend fun validToken(): SpotifyWebToken = tokenMutex.withLock {
        cachedToken?.takeIf { it.expiresAtMs > System.currentTimeMillis() + 60_000 }
            ?: error("Sesja Spotify wygasła. Zaloguj się ponownie.")
    }

    private suspend fun searchTracks(query: String, token: SpotifyWebToken): List<Track> {
        return searchPayload(query, token).tracks
    }

    private suspend fun searchPlaylists(query: String, token: SpotifyWebToken): List<Playlist> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val root = getJson("https://api.spotify.com/v1/search?q=$encoded&type=playlist&limit=10", token).jsonObject
        return (root["playlists"]?.jsonObject?.get("items") as? JsonArray).orEmpty().mapNotNull { item ->
            val obj = item.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Playlist(id, obj["name"]?.jsonPrimitive?.contentOrNull ?: "Playlist", obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(), emptyList(), (obj["images"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull)
        }
    }

    private data class SearchPayload(val tracks: List<Track>, val root: JsonObject)

    private suspend fun searchPayload(query: String, token: SpotifyWebToken): SearchPayload {
        val body = JsonObject(mapOf(
            "variables" to JsonObject(mapOf(
                "searchTerm" to JsonPrimitive(query), "offset" to JsonPrimitive(0), "limit" to JsonPrimitive(50),
                "numberOfTopResults" to JsonPrimitive(50), "includeAudiobooks" to JsonPrimitive(false),
                "includeArtistHasConcertsField" to JsonPrimitive(false), "includePreReleases" to JsonPrimitive(false),
                "includeLocalConcertsField" to JsonPrimitive(false), "includeAuthors" to JsonPrimitive(false),
            )),
            "operationName" to JsonPrimitive("searchTracks"),
            "extensions" to JsonObject(mapOf("persistedQuery" to JsonObject(mapOf(
                "version" to JsonPrimitive(1),
                "sha256Hash" to JsonPrimitive(SearchTracksHash),
            )))),
        )).toString()
        val root = postJson("https://api-partner.spotify.com/pathfinder/v2/query", body, token).jsonObject
        val items = root["data"]?.jsonObject?.get("searchV2")?.jsonObject?.get("tracksV2")?.jsonObject?.get("items") as? JsonArray
        val tracks = items.orEmpty().mapNotNull { element ->
            val item = element.jsonObject["item"]?.jsonObject?.get("data")?.jsonObject ?: return@mapNotNull null
            val uri = item["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val id = uri.substringAfterLast(':').takeIf(String::isNotBlank) ?: return@mapNotNull null
            val artists = item["artists"]?.jsonObject?.get("items") as? JsonArray
            val durationMs = item["trackDuration"]?.jsonObject?.get("totalMilliseconds")?.jsonPrimitive?.content?.toLongOrNull()
                ?: item["duration"]?.jsonObject?.get("totalMilliseconds")?.jsonPrimitive?.content?.toLongOrNull()
                ?: item["duration"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: item["durationMs"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: 0L
            Track(
                id = id,
                title = item["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                artists = artists.orEmpty().mapNotNull {
                    it.jsonObject["profile"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                },
                album = item["albumOfTrack"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
                durationMs = durationMs,
                imageUrl = spotifyImageUrl(item["albumOfTrack"] ?: item),
            )
        }.distinctBy(Track::id).take(50)
        return SearchPayload(tracks, root)
    }

    private fun collectArtists(element: kotlinx.serialization.json.JsonElement): List<Artist> = when (element) {
        is JsonArray -> element.flatMap(::collectArtists)
        is JsonObject -> {
            val uri = element["uri"]?.jsonPrimitive?.contentOrNull
            val current = if (uri?.startsWith("spotify:artist:") == true) {
                element["name"]?.jsonPrimitive?.contentOrNull?.let { Artist(uri.substringAfterLast(':'), it, spotifyImageUrl(element)) }
            } else null
            listOfNotNull(current) + element.values.flatMap(::collectArtists)
        }
        else -> emptyList()
    }.distinctBy(Artist::id)

    private fun collectPlaylists(element: kotlinx.serialization.json.JsonElement): List<Playlist> = when (element) {
        is JsonArray -> element.flatMap(::collectPlaylists)
        is JsonObject -> {
            val uri = element["uri"]?.jsonPrimitive?.contentOrNull
            val current = if (uri?.startsWith("spotify:playlist:") == true) {
                val name = element["name"]?.jsonPrimitive?.contentOrNull
                    ?: element["title"]?.jsonObject?.get("transformedLabel")?.jsonPrimitive?.contentOrNull
                name?.let { Playlist(uri.substringAfterLast(':'), it, playlistDescription(element), emptyList(), spotifyImageUrl(element)) }
            } else null
            listOfNotNull(current) + element.values.flatMap(::collectPlaylists)
        }
        else -> emptyList()
    }

    private fun playlistDescription(item: JsonObject): String =
        (item["description"] as? JsonPrimitive)?.contentOrNull
            ?: item["ownerV2"]?.jsonObject?.get("data")?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull?.let { "Spotify • $it" }
            ?: "Rekomendacja Spotify"

    private fun collectTracks(element: kotlinx.serialization.json.JsonElement): List<Track> = when (element) {
        is JsonArray -> element.flatMap(::collectTracks)
        is JsonObject -> {
            val uri = (element["uri"] as? JsonPrimitive)?.contentOrNull
            val current = if (uri?.startsWith("spotify:track:") == true) mapGraphTrack(element, uri) else null
            listOfNotNull(current) + element.values.flatMap(::collectTracks)
        }
        else -> emptyList()
    }

    private fun mapGraphTrack(item: JsonObject, uri: String): Track? {
        val title = (item["name"] as? JsonPrimitive)?.contentOrNull ?: return null
        val artistItems = item["artists"]?.jsonObject?.get("items") as? JsonArray
        val artists = artistItems.orEmpty().mapNotNull {
            it.jsonObject["profile"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                ?: (it.jsonObject["name"] as? JsonPrimitive)?.contentOrNull
        }
        val durationMs = item["trackDuration"]?.jsonObject?.get("totalMilliseconds")?.jsonPrimitive?.content?.toLongOrNull()
            ?: item["duration"]?.jsonObject?.get("totalMilliseconds")?.jsonPrimitive?.content?.toLongOrNull()
            ?: item["durationMs"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: 0L
        return Track(
            uri.substringAfterLast(':'), title, artists,
            item["albumOfTrack"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
            durationMs,
            spotifyImageUrl(item["albumOfTrack"] ?: item),
        )
    }

    private fun spotifyImageUrl(element: kotlinx.serialization.json.JsonElement): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull?.takeIf { it.startsWith("https://") && ("scdn.co" in it || "spotifycdn" in it) }
        is JsonArray -> element.firstNotNullOfOrNull(::spotifyImageUrl)
        is JsonObject -> {
            val priority = listOf("sources", "images", "coverArt", "image")
            priority.firstNotNullOfOrNull { key -> element[key]?.let(::spotifyImageUrl) }
                ?: element.values.firstNotNullOfOrNull(::spotifyImageUrl)
        }
        else -> null
    }

    private fun mapLibraryTrack(element: kotlinx.serialization.json.JsonElement): Track? {
        val item = element as? JsonObject ?: return null
        val wrapper = item["track"] as? JsonObject ?: return null
        val uri = wrapper["_uri"]?.jsonPrimitive?.contentOrNull ?: return null
        val data = wrapper["data"] as? JsonObject ?: return null
        return mapGraphTrack(data, uri)
    }

    private suspend fun postJson(url: String, body: String, token: SpotifyWebToken): kotlinx.serialization.json.JsonElement {
        val builder = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", BrowserUserAgent)
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        token.value.use { builder.header("Authorization", "Bearer $it") }
        val response = send(builder.build())
        logger.debug("SpotifySearch", "graphql_response_received", "Odebrano odpowiedź wyszukiwania", fields = mapOf("statusCode" to response.statusCode().toString()))
        require(response.statusCode() in 200..299) { "Spotify GraphQL HTTP ${response.statusCode()}" }
        return json.parseToJsonElement(response.body())
    }

    private suspend fun getJson(url: String, token: SpotifyWebToken): kotlinx.serialization.json.JsonElement {
        val builder = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20))
            .header("User-Agent", BrowserUserAgent).header("Accept", "application/json")
            .header("App-Platform", "WebPlayer").GET()
        token.value.use { builder.header("Authorization", "Bearer $it") }
        val response = send(builder.build())
        require(response.statusCode() in 200..299) { "Spotify radio HTTP ${response.statusCode()}" }
        return json.parseToJsonElement(response.body())
    }

    private suspend fun libraryRequest(method: String, action: String?, trackId: String): kotlinx.serialization.json.JsonElement? {
        require(trackId.matches(Regex("[A-Za-z0-9]+"))) { "Nieprawidłowy identyfikator utworu" }
        val token = validToken()
        val spotifyUri = URLEncoder.encode("spotify:track:$trackId", StandardCharsets.UTF_8)
        val suffix = action?.let { "/$it" }.orEmpty()
        val builder = HttpRequest.newBuilder(URI("https://api.spotify.com/v1/me/library$suffix?uris=$spotifyUri"))
            .timeout(Duration.ofSeconds(20)).header("Accept", "application/json")
        token.value.use { builder.header("Authorization", "Bearer $it") }
        when (method) {
            "GET" -> builder.GET()
            "PUT" -> builder.PUT(HttpRequest.BodyPublishers.noBody())
            "DELETE" -> builder.DELETE()
            else -> error("Nieobsługiwana metoda HTTP")
        }
        val response = send(builder.build())
        check(response.statusCode() in 200..299) { "Spotify library HTTP ${response.statusCode()}: ${response.body().take(120)}" }
        return response.body().takeIf(String::isNotBlank)?.let(json::parseToJsonElement)
    }

    private suspend fun send(request: HttpRequest): HttpResponse<String> = withContext(Dispatchers.IO) {
        client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        private const val SearchTracksHash = "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428"
        private const val HomeHash = "76243c78b0e20ecdbe41b794dec8cbe73f75e585b0a7201b8d2e84578412847a"
        private const val PlaylistContentsHash = "a65e12194ed5fc443a1cdebed5fabe33ca5b07b987185d63c72483867ad13cb4"
        private const val LibraryTracksHash = "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240"
        private const val BrowserUserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/128.0 Safari/537.36"
    }
}
