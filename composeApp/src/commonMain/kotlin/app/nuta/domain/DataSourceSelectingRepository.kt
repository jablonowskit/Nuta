package app.nuta.domain

import app.nuta.core.models.Playlist
import app.nuta.core.models.SearchResult
import app.nuta.core.models.Track
import app.nuta.settings.DataSource
import app.nuta.settings.PlaybackSettingsStore

/**
 * Delegates to whichever concrete repository matches the user's `DataSource` setting, read
 * fresh on every call (so switching in Settings takes effect immediately, no restart needed).
 * Full symmetry — after switching to ListenBrainz, no method reaches Spotify (and vice versa),
 * mirroring `SourceSelectingMediaService`'s pattern for audio sources.
 */
class DataSourceSelectingRepository(
    private val settingsStore: PlaybackSettingsStore,
    private val spotify: SpotifyRepository,
    private val listenBrainz: SpotifyRepository,
) : SpotifyRepository {
    private fun active() =
        if (settingsStore.settings.value.dataSource == DataSource.LISTENBRAINZ) listenBrainz else spotify

    override suspend fun getPlaylists(): List<Playlist> = active().getPlaylists()
    override suspend fun getSavedPlaylists(): List<Playlist> = active().getSavedPlaylists()
    override suspend fun getPlaylistTracks(playlistId: String): List<Track> = active().getPlaylistTracks(playlistId)
    override suspend fun getLikedTracks(): List<Track> = active().getLikedTracks()
    override suspend fun getCachedLikedTracks(): List<Track> = active().getCachedLikedTracks()
    override suspend fun isTrackLiked(trackId: String): Boolean = active().isTrackLiked(trackId)
    override suspend fun setTrackLiked(track: Track, liked: Boolean) = active().setTrackLiked(track, liked)
    override suspend fun search(query: String): SearchResult = active().search(query)
    override suspend fun getTrackRadio(seed: Track, limit: Int): List<Track> = active().getTrackRadio(seed, limit)
    override suspend fun createPlaylist(name: String, description: String): Playlist = active().createPlaylist(name, description)
    override suspend fun addTracksToPlaylist(playlistId: String, tracks: List<Track>) = active().addTracksToPlaylist(playlistId, tracks)
}
