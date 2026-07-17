package app.nuta

import app.nuta.core.logging.MemoryLogger
import app.nuta.domain.AudioPlayer
import app.nuta.domain.SpotifyRepository
import app.nuta.youtube.YouTubeMediaService

data class AppContainer(
    val spotifyRepository: SpotifyRepository,
    val audioPlayer: AudioPlayer,
    val logger: MemoryLogger,
    val youtubeMediaService: YouTubeMediaService? = null,
)
