package app.nuta.domain

import app.nuta.core.models.PlayerState
import app.nuta.core.models.Playlist
import app.nuta.core.models.SearchResult
import app.nuta.core.models.Track
import kotlinx.coroutines.flow.StateFlow

interface SpotifyRepository {
    suspend fun getPlaylists(): List<Playlist>
    suspend fun getPlaylistTracks(playlistId: String): List<Track>
    suspend fun search(query: String): SearchResult
    suspend fun getTrackRadio(seed: Track, limit: Int = 20): List<Track>
}

interface AudioPlayer {
    val state: StateFlow<PlayerState>
    suspend fun setQueue(tracks: List<Track>, startIndex: Int = 0)
    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    suspend fun seekTo(positionMs: Long)
    suspend fun next()
    suspend fun previous()
    suspend fun playAt(index: Int)
    suspend fun simulateError()
}
