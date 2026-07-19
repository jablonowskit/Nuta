package app.nuta.domain

import app.nuta.core.models.PlayerState
import app.nuta.core.models.Playlist
import app.nuta.core.models.SearchResult
import app.nuta.core.models.Track
import kotlinx.coroutines.flow.StateFlow

interface SpotifyRepository {
    suspend fun getPlaylists(): List<Playlist>
    suspend fun getSavedPlaylists(): List<Playlist>
    suspend fun getPlaylistTracks(playlistId: String): List<Track>
    suspend fun getLikedTracks(): List<Track>
    /** Ostatnia znana lista ulubionych bez sięgania do sieci — do natychmiastowego stanu serduszka po starcie. */
    suspend fun getCachedLikedTracks(): List<Track> = emptyList()
    suspend fun isTrackLiked(trackId: String): Boolean
    suspend fun setTrackLiked(trackId: String, liked: Boolean)
    suspend fun search(query: String): SearchResult
    suspend fun getTrackRadio(seed: Track, limit: Int = 20): List<Track>
}

interface AudioPlayer {
    val state: StateFlow<PlayerState>
    suspend fun setQueue(tracks: List<Track>, startIndex: Int = 0)
    suspend fun appendToQueue(tracks: List<Track>)
    suspend fun shuffleUpcoming()
    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    suspend fun seekTo(positionMs: Long)
    suspend fun next()
    suspend fun previous()
    suspend fun playAt(index: Int)
    suspend fun simulateError()
    /** Eksperymentalne: z góry rozwiąż strumień dla podanych utworów, żeby kliknięcie play było natychmiastowe. No-op domyślnie. */
    suspend fun prefetch(tracks: List<Track>) {}
}
