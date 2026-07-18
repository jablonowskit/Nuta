package app.nuta.core.models

data class Track(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String,
    val durationMs: Long,
)

data class Playlist(
    val id: String,
    val name: String,
    val description: String,
    val tracks: List<Track>,
)

data class SearchResult(
    val tracks: List<Track>,
    val playlists: List<Playlist>,
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
) {
    val currentTrack: Track? get() = queue.getOrNull(currentIndex)
    val durationMs: Long get() = currentTrack?.durationMs ?: 0L
}

enum class Destination(val label: String) {
    HOME("Start"),
    PLAYLISTS("Playlisty"),
    LIKED("Ulubione"),
    SEARCH("Wyszukiwanie"),
    QUEUE("Kolejka"),
    SETTINGS("Ustawienia"),
    DIAGNOSTICS("Diagnostyka"),
}
