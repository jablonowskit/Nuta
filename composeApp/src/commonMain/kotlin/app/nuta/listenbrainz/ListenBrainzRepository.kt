package app.nuta.listenbrainz

import app.nuta.core.logging.NutaLogger
import app.nuta.core.models.Playlist
import app.nuta.core.models.SearchResult
import app.nuta.core.models.Track
import app.nuta.domain.SpotifyRepository
import app.nuta.musicbrainz.MusicBrainzRepository
import app.nuta.net.httpGet
import app.nuta.net.httpPost
import app.nuta.settings.PlaybackSettingsStore
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Implementacja `SpotifyRepository` w oparciu o ListenBrainz (rekomendacje/playlisty/ulubione)
 * + MusicBrainz (wyszukiwanie, przez [musicBrainz]) — pełna symetria ze Spotify, żadnego
 * mergowania między serwisami. Nazwa użytkownika i token API czytane świeżo z [settingsStore]
 * przy każdym wywołaniu (ten sam wzorzec co inne resolvery czytające ustawienia na bieżąco).
 * Wszystkie endpointy zweryfikowane curlem 2026-08-23, patrz plan "ListenBrainz/MusicBrainz
 * jako alternatywne źródło danych".
 */
class ListenBrainzRepository(
    private val settingsStore: PlaybackSettingsStore,
    private val musicBrainz: MusicBrainzRepository,
    private val logger: NutaLogger,
) : SpotifyRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private var cachedRecommendations: List<Track>? = null

    private fun username() = settingsStore.settings.value.listenBrainzUsername
    private fun apiToken() = settingsStore.settings.value.listenBrainzApiToken

    private fun requireToken(): String {
        val token = apiToken()
        check(token.isNotBlank()) { "Skonfiguruj token ListenBrainz w Ustawieniach" }
        return token
    }

    override suspend fun getPlaylists(): List<Playlist> {
        val user = username()
        val recommendations = fetchRecommendations(user)
        cachedRecommendations = recommendations
        val real = fetchUserPlaylists(user)
        val generated = fetchGeneratedPlaylists(user)
        val synthetic = if (recommendations.isNotEmpty()) {
            listOf(Playlist(id = SyntheticRecommendationsId, name = "Rekomendacje ListenBrainz", description = "", tracks = recommendations))
        } else emptyList()
        return synthetic + generated + real
    }

    override suspend fun getSavedPlaylists(): List<Playlist> = fetchUserPlaylists(username())

    override suspend fun getPlaylistTracks(playlistId: String): List<Track> {
        if (playlistId == SyntheticRecommendationsId) {
            cachedRecommendations?.let { return it }
            getPlaylists()
            return cachedRecommendations ?: emptyList()
        }
        val trackList = runCatching {
            val response = httpGet("https://api.listenbrainz.org/1/playlist/$playlistId")
            if (response.isBlank()) return@runCatching null
            json.parseToJsonElement(response).jsonObject["playlist"]?.jsonObject?.get("track") as? JsonArray
        }.getOrElse { error ->
            logger.warn("ListenBrainz", "playlist_tracks_failed", "Nie udało się pobrać playlisty ListenBrainz", fields = mapOf("playlistId" to playlistId, "reason" to (error.message ?: "unknown")))
            null
        } ?: return emptyList()
        return trackList.mapNotNull(::trackFromJspf)
    }

    /** `metadata=true` zwraca tytuł/artystę wprost w odpowiedzi (dla wpisów z recording_mbid
        i recording_msid jednakowo) — jedno zapytanie na stronę, bez osobnego batch-lookupu.
        Endpoint domyślnie zwraca tylko pierwsze 25 wyników — trzeba paginować przez offset,
        żeby dostać wszystkie polubienia, nie tylko pierwszą stronę. Nie zawiera jednak długości
        utworu — bez niej `PlayerState.durationMs` wychodzi 0, a `seekTo()` (przycięte do
        `0..durationMs`) zawsze wraca na początek. Dlatego wpisy z `recording_mbid` dociągają
        prawdziwą długość przez [lookupMetadata] (batch `/metadata/recording/`, ma pole
        `length`); tylko wpisy z gołym `recording_msid` (bez MBID) zostają z durationMs=0. */
    override suspend fun getLikedTracks(): List<Track> {
        val user = username()
        if (user.isBlank()) return emptyList()
        data class Inline(val id: String, val hasMbid: Boolean, val title: String, val artist: String, val album: String, val artistMbid: String?)
        val inline = mutableListOf<Inline>()
        var offset = 0
        while (true) {
            val page = runCatching {
                val response = httpGet("https://api.listenbrainz.org/1/feedback/user/$user/get-feedback?score=1&metadata=true&count=100&offset=$offset")
                if (response.isBlank()) return@runCatching null
                json.parseToJsonElement(response).jsonObject
            }.getOrElse { error ->
                logger.info("ListenBrainz", "no_feedback", "Brak polubień ListenBrainz lub nieznany użytkownik", fields = mapOf("reason" to (error.message ?: "unknown")))
                null
            } ?: break
            val feedback = page["feedback"] as? JsonArray ?: break
            feedback.forEach { item ->
                val entry = item as? JsonObject ?: return@forEach
                val mbid = entry["recording_mbid"]?.jsonPrimitive?.contentOrNull
                val id = mbid ?: entry["recording_msid"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val metadata = entry["track_metadata"] as? JsonObject ?: return@forEach
                val title = metadata["track_name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val artist = metadata["artist_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val album = metadata["release_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                // mbid_mapping bywa jawnym JSON null (nie brakującym polem) dla wpisów bez
                // dopasowania — .jsonObject rzuca na JsonNull zamiast zwrócić null, stąd `as?`.
                val artistMbid = (metadata["mbid_mapping"] as? JsonObject)?.get("artist_mbids")?.let { it as? JsonArray }
                    ?.firstOrNull()?.jsonPrimitive?.contentOrNull
                inline += Inline(id, mbid != null, title, artist, album, artistMbid)
            }
            val totalCount = page["total_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: inline.size
            offset += feedback.size
            if (feedback.isEmpty() || offset >= totalCount) break
        }
        val durationByMbid = lookupMetadata(inline.filter { it.hasMbid }.map(Inline::id)).associate { it.id to it.durationMs }
        return inline.map {
            Track(
                id = it.id,
                title = it.title,
                artists = listOfNotNull(it.artist.takeIf(String::isNotBlank)),
                album = it.album,
                durationMs = durationByMbid[it.id] ?: 0L,
                artistMbid = it.artistMbid,
            )
        }
    }

    override suspend fun isTrackLiked(trackId: String): Boolean = getLikedTracks().any { it.id == trackId }

    override suspend fun setTrackLiked(track: Track, liked: Boolean) {
        val mbid = resolveMbid(track)
            ?: throw IllegalStateException("Nie znaleziono odpowiednika utworu '${track.title}' w MusicBrainz — nie można polubić")
        val token = requireToken()
        val body = JsonObject(mapOf(
            "recording_mbid" to JsonPrimitive(mbid),
            "score" to JsonPrimitive(if (liked) 1 else 0),
        )).toString()
        httpPost("https://api.listenbrainz.org/1/feedback/recording-feedback", headers = mapOf("Authorization" to "Token $token"), body = body)
        logger.info("ListenBrainz", "feedback_set", "Zapisano polubienie ListenBrainz", fields = mapOf("trackId" to mbid, "liked" to liked.toString()))
    }

    /** Identyfikatory utworów mogą pochodzić z innego źródła danych (np. Spotify, sprzed
        przełączenia na ListenBrainz) i wtedy nie są prawidłowymi MBID — w takim wypadku szukamy
        odpowiednika po tytule i wykonawcy w MusicBrainz. Zwraca null, jeśli nic nie pasuje. */
    private suspend fun resolveMbid(track: Track): String? {
        if (MbidRegex.matches(track.id)) return track.id
        val query = "${track.title} ${track.artists.firstOrNull().orEmpty()}".trim()
        val found = musicBrainz.search(query).tracks.firstOrNull()?.id
        if (found != null) {
            logger.info(
                "ListenBrainz", "mbid_resolved_via_search",
                "Rozwiązano MBID przez wyszukiwanie MusicBrainz (ID z innego źródła danych)",
                fields = mapOf("trackId" to track.id, "title" to track.title, "resolvedMbid" to found),
            )
        }
        return found
    }

    override suspend fun search(query: String): SearchResult = musicBrainz.search(query)

    override suspend fun getTrackRadio(seed: Track, limit: Int): List<Track> {
        val token = requireToken()
        val artistMbid = seed.artistMbid
        if (artistMbid == null) {
            logger.warn("ListenBrainz", "missing_artist_mbid", "Brak MBID artysty w utworze-ziarnie — nie można zbudować promptu lb-radio", fields = mapOf("trackId" to seed.id))
            return emptyList()
        }
        val prompt = URLEncoder.encode("artist:($artistMbid)", "UTF-8")
        val trackList = runCatching {
            val response = httpGet(
                "https://api.listenbrainz.org/1/explore/lb-radio?prompt=$prompt&mode=easy",
                headers = mapOf("Authorization" to "Token $token"),
            )
            if (response.isBlank()) return@runCatching null
            json.parseToJsonElement(response).jsonObject["payload"]?.jsonObject
                ?.get("jspf")?.jsonObject?.get("playlist")?.jsonObject?.get("track") as? JsonArray
        }.getOrElse { error ->
            logger.warn("ListenBrainz", "lb_radio_failed", "Nie udało się pobrać lb-radio", fields = mapOf("reason" to (error.message ?: "unknown")))
            null
        } ?: return emptyList()
        return trackList.mapNotNull(::trackFromJspf).take(limit)
    }

    override suspend fun createPlaylist(name: String, description: String): Playlist {
        val token = requireToken()
        val body = JsonObject(mapOf(
            "playlist" to JsonObject(mapOf(
                "title" to JsonPrimitive(name),
                "annotation" to JsonPrimitive(description),
                "extension" to JsonObject(mapOf(
                    "https://musicbrainz.org/doc/jspf#playlist" to JsonObject(mapOf("public" to JsonPrimitive(true))),
                )),
            )),
        )).toString()
        val response = httpPost("https://api.listenbrainz.org/1/playlist/create", headers = mapOf("Authorization" to "Token $token"), body = body)
        val playlistMbid = response.takeIf(String::isNotBlank)?.let { json.parseToJsonElement(it).jsonObject["playlist_mbid"]?.jsonPrimitive?.contentOrNull }
            ?: error("ListenBrainz nie zwrócił identyfikatora nowej playlisty")
        logger.info("ListenBrainz", "playlist_created", "Utworzono playlistę ListenBrainz", fields = mapOf("playlistId" to playlistMbid))
        return Playlist(id = playlistMbid, name = name, description = description, tracks = emptyList())
    }

    override suspend fun addTracksToPlaylist(playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val mbids = tracks.mapNotNull { track ->
            val mbid = resolveMbid(track)
            if (mbid == null) {
                logger.warn("ListenBrainz", "mbid_resolution_failed", "Pominięto utwór bez odpowiednika w MusicBrainz", fields = mapOf("trackId" to track.id, "title" to track.title))
            }
            mbid
        }
        if (mbids.isEmpty()) return
        val token = requireToken()
        val body = JsonObject(mapOf(
            "playlist" to JsonObject(mapOf(
                "track" to JsonArray(mbids.map { mbid ->
                    JsonObject(mapOf("identifier" to JsonArray(listOf(JsonPrimitive("https://musicbrainz.org/recording/$mbid")))))
                }),
            )),
        )).toString()
        httpPost("https://api.listenbrainz.org/1/playlist/$playlistId/item/add", headers = mapOf("Authorization" to "Token $token"), body = body)
        logger.info("ListenBrainz", "tracks_added", "Dodano utwory do playlisty ListenBrainz", fields = mapOf("playlistId" to playlistId, "count" to mbids.size.toString()))
    }

    private suspend fun fetchRecommendations(user: String): List<Track> {
        if (user.isBlank()) return emptyList()
        val mbidsArray = runCatching {
            val response = httpGet("https://api.listenbrainz.org/1/cf/recommendation/user/$user/recording?count=60")
            if (response.isBlank()) return@runCatching null
            val root = json.parseToJsonElement(response).jsonObject
            root["payload"]?.jsonObject?.get("mbids") as? JsonArray ?: root["mbids"] as? JsonArray
        }.getOrElse { error ->
            logger.info("ListenBrainz", "no_recommendations_model", "Brak wyliczonego modelu rekomendacji ListenBrainz", fields = mapOf("reason" to (error.message ?: "unknown")))
            null
        } ?: return emptyList()
        val mbids = mbidsArray.mapNotNull { it.jsonObject["recording_mbid"]?.jsonPrimitive?.contentOrNull }
        return lookupMetadata(mbids)
    }

    private suspend fun fetchUserPlaylists(user: String): List<Playlist> {
        if (user.isBlank()) return emptyList()
        val playlists = fetchPlaylistsJson(user, "https://api.listenbrainz.org/1/user/$user/playlists") ?: return emptyList()
        return playlists.mapNotNull { item ->
            val playlist = item.jsonObject["playlist"]?.jsonObject ?: return@mapNotNull null
            val identifier = playlist["identifier"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val mbid = identifier.substringAfterLast('/')
            val title = playlist["title"]?.jsonPrimitive?.contentOrNull ?: "Playlista"
            Playlist(id = mbid, name = title, description = "", tracks = emptyList())
        }
    }

    /** Automatycznie generowane przez ListenBrainz playlisty ("troi-bot") — odpowiednik
        Spotify Daily Mix/Discover Weekly/Year in Review. Endpoint zwraca też dziesiątki
        historycznych wpisów (poprzednie tygodnie, roczne podsumowania) — nie ucinamy ich, tylko
        sortujemy od najnowszych; Home ujawnia je stopniowo przy przewijaniu tym samym
        mechanizmem, który już działa dla playlist Spotify (INITIAL_RECOMMENDATIONS_COUNT /
        RECOMMENDATIONS_PAGE_SIZE), więc świeże playlisty są widoczne od razu, a starsze —
        dalej na liście. */
    private suspend fun fetchGeneratedPlaylists(user: String): List<Playlist> {
        if (user.isBlank()) return emptyList()
        val playlists = fetchPlaylistsJson(user, "https://api.listenbrainz.org/1/user/$user/playlists/createdfor?count=100") ?: return emptyList()
        return playlists.mapNotNull { item ->
            val playlist = item.jsonObject["playlist"]?.jsonObject ?: return@mapNotNull null
            val identifier = playlist["identifier"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val mbid = identifier.substringAfterLast('/')
            val title = playlist["title"]?.jsonPrimitive?.contentOrNull ?: "Playlista"
            val date = playlist["date"]?.jsonPrimitive?.contentOrNull ?: ""
            date to Playlist(id = mbid, name = title, description = "", tracks = emptyList())
        }.sortedByDescending { it.first }.map { it.second }
    }

    private suspend fun fetchPlaylistsJson(user: String, url: String): JsonArray? = runCatching {
        val response = httpGet(url)
        if (response.isBlank()) return@runCatching null
        json.parseToJsonElement(response).jsonObject["playlists"] as? JsonArray
    }.getOrElse { error ->
        logger.warn("ListenBrainz", "playlists_unavailable", "Nie udało się pobrać playlist ListenBrainz", fields = mapOf("user" to user, "reason" to (error.message ?: "unknown")))
        null
    }

    /** Batch lookup metadanych po MBID nagrania — kształt odpowiedzi ListenBrainz różni się od
        MusicBrainz search (`artist.artists[].name`, nie `artist-credit[].artist.name`), więc ten
        parser jest specyficzny dla ListenBrainz i nie jest dzielony z [MusicBrainzRepository]. */
    private suspend fun lookupMetadata(mbids: List<String>): List<Track> {
        if (mbids.isEmpty()) return emptyList()
        val tracks = mutableListOf<Track>()
        mbids.distinct().chunked(50).forEach { chunk ->
            val root = runCatching {
                val response = httpGet("https://api.listenbrainz.org/1/metadata/recording/?recording_mbids=${chunk.joinToString(",")}&inc=artist")
                if (response.isBlank()) return@runCatching null
                json.parseToJsonElement(response).jsonObject
            }.getOrElse { error ->
                logger.warn("ListenBrainz", "metadata_lookup_failed", "Nie udało się dociągnąć metadanych nagrań", fields = mapOf("reason" to (error.message ?: "unknown")))
                null
            } ?: return@forEach
            chunk.forEach { mbid ->
                val entry = root[mbid]?.jsonObject ?: return@forEach
                val recording = entry["recording"]?.jsonObject
                val name = recording?.get("name")?.jsonPrimitive?.contentOrNull ?: return@forEach
                val length = recording["length"]?.jsonPrimitive?.longOrNull ?: 0L
                val artistObj = entry["artist"]?.jsonObject
                val artists = (artistObj?.get("artists") as? JsonArray).orEmpty()
                val artistNames = artists.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                val artistMbid = artists.firstOrNull()?.jsonObject?.get("artist_mbid")?.jsonPrimitive?.contentOrNull
                tracks += Track(id = mbid, title = name, artists = artistNames, album = "", durationMs = length, artistMbid = artistMbid)
            }
        }
        return tracks
    }

    /** Wspólny parser dla JSPF-podobnych odpowiedzi ListenBrainz (`playlist.track[]` i
        `payload.jspf.playlist.track[]` z lb-radio) — obie mają ten sam kształt pojedynczego
        elementu (`identifier`, `title`, `creator`, `duration`), więc wystarczy jeden parser. */
    private fun trackFromJspf(element: kotlinx.serialization.json.JsonElement): Track? {
        val obj = element.jsonObject
        val identifier = (obj["identifier"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull
            ?: obj["identifier"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val mbid = identifier.substringAfterLast('/')
        val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return null
        val creator = obj["creator"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val duration = obj["duration"]?.jsonPrimitive?.longOrNull ?: 0L
        return Track(id = mbid, title = title, artists = listOfNotNull(creator.takeIf(String::isNotBlank)), album = "", durationMs = duration)
    }

    private companion object {
        const val SyntheticRecommendationsId = "listenbrainz-recommendations"
        val MbidRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
