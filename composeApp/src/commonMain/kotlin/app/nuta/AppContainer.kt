package app.nuta

import app.nuta.core.logging.MemoryLogger
import app.nuta.domain.AudioPlayer
import app.nuta.domain.SpotifyRepository
import app.nuta.player.QueueContinuation
import app.nuta.player.SleepTimer
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
    /** Powrót sieci — UI odświeża listy. Desktop: emptyFlow. */
    val networkResumed: Flow<Unit> = emptyFlow(),
    /** Wywołane gdy Spotify zwróci 401 — Android czyści token. */
    val onSpotifySessionInvalid: () -> Unit = {},
    /**
     * Keystore nie odszyfrował tokenu LB. Getter (nie stała bool), żeby Compose czytał
     * świeżą wartość przy każdym sprawdzeniu — inaczej `remember` zamrażał stan ze startu.
     */
    val listenBrainzTokenUnreadable: () -> Boolean = { false },
    /** Jeden na proces, przekazywany do każdego nowego kontenera — patrz [SleepTimer]. */
    val sleepTimer: SleepTimer = SleepTimer(player = { audioPlayer }),
    /** Jak [sleepTimer]: jeden na proces, żyje poza UI (tryb podobnych ♬+ i „Graj dalej”). */
    val queueContinuation: QueueContinuation = QueueContinuation(playbackSettings, { spotifyRepository }, logger),
)
