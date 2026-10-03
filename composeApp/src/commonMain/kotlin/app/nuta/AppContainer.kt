package app.nuta

import app.nuta.core.logging.MemoryLogger
import app.nuta.domain.AudioPlayer
import app.nuta.domain.SpotifyRepository
import app.nuta.youtube.YouTubeMediaService
import app.nuta.settings.InMemoryPlaybackSettingsStore
import app.nuta.settings.PlaybackSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class AppContainer(
    val spotifyRepository: SpotifyRepository,
    val audioPlayer: AudioPlayer,
    val logger: MemoryLogger,
    val youtubeMediaService: YouTubeMediaService? = null,
    val playbackSettings: PlaybackSettingsStore = InMemoryPlaybackSettingsStore(),
    /** Powrót sieci / wznowienie — UI odświeża listy. Desktop: emptyFlow. */
    val networkResumed: Flow<Unit> = emptyFlow(),
    /** Wywołane gdy Spotify zwróci 401/403 — Android czyści token. */
    val onSpotifySessionInvalid: () -> Unit = {},
    /** Keystore nie odszyfrował tokenu LB — komunikat w Ustawieniach. */
    val listenBrainzTokenUnreadable: Boolean = false,
)
