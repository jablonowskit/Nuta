package app.nuta

import app.nuta.core.logging.MemoryLogger
import app.nuta.domain.AudioPlayer
import app.nuta.domain.SpotifyRepository
import app.nuta.youtube.YouTubeMediaService
import app.nuta.settings.InMemoryPlaybackSettingsStore
import app.nuta.settings.PlaybackSettingsStore

data class AppContainer(
    val spotifyRepository: SpotifyRepository,
    val audioPlayer: AudioPlayer,
    val logger: MemoryLogger,
    val youtubeMediaService: YouTubeMediaService? = null,
    val playbackSettings: PlaybackSettingsStore = InMemoryPlaybackSettingsStore(),
)
