package app.nuta.core.models

data class Track(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String,
    val durationMs: Long,
    val imageUrl: String? = null,
    /** MBID artysty (ListenBrainz/MusicBrainz) — potrzebny do budowy promptu `artist:(...)` w lb-radio. */
    val artistMbid: String? = null,
)

data class Playlist(
    val id: String,
    val name: String,
    val description: String,
    val tracks: List<Track>,
    val imageUrl: String? = null,
)

data class Artist(
    val id: String,
    val name: String,
    val imageUrl: String? = null,
)

data class SearchResult(
    val tracks: List<Track>,
    val playlists: List<Playlist>,
    val artists: List<Artist> = emptyList(),
    /**
     * true, gdy wyszukiwanie playlist zawiodło, mimo że reszta wyników przyszła poprawnie
     * (oba zapytania lecą równolegle — patrz ListenBrainzRepository.search). Bez tego pustą
     * listę playlist z awarii serwisu widać w UI tak samo jak realny brak trafień.
     */
    val playlistsUnavailable: Boolean = false,
    /** Utwory już są, playlisty jeszcze się wyszukują (patrz SpotifyRepository.searchProgressive). */
    val playlistsLoading: Boolean = false,
    /** Utwory jeszcze się wyszukują — inne części mogły już dojść (patrz searchProgressive). */
    val tracksLoading: Boolean = false,
)

enum class PlayerStatus { IDLE, LOADING, PLAYING, PAUSED, ENDED, ERROR }

data class PlayerState(
    val status: PlayerStatus = PlayerStatus.IDLE,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val positionMs: Long = 0,
    val errorMessage: String? = null,
    val streamBitrate: Int? = null,
    val streamCodec: String? = null,
    val shuffleEnabled: Boolean = false,
) {
    val currentTrack: Track? get() = queue.getOrNull(currentIndex)
    val durationMs: Long get() = currentTrack?.durationMs ?: 0L
}

enum class Destination {
    HOME,
    PLAYLISTS,
    LIKED,
    SEARCH,
    QUEUE,
    SETTINGS,
    DIAGNOSTICS,
}
