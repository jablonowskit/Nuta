package app.nuta.android

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.nuta.core.logging.LogLevel
import app.nuta.core.logging.MemoryLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

/**
 * Usługi o czasie życia procesu. Player i MediaController nie mogą należeć do Activity:
 * serwis odtwarzania (i kontrolki na ekranie blokady) żyją dłużej niż UI.
 */
object AppServices {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @Volatile private var started = false

    lateinit var logger: MemoryLogger
        private set
    lateinit var playbackSettings: AndroidPlaybackSettingsStore
        private set
    lateinit var youtubeMediaService: AndroidYouTubeMediaService
        private set

    val audioPlayer = MutableStateFlow<Media3AudioPlayer?>(null)
    val playerError = MutableStateFlow<String?>(null)

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        logger = MemoryLogger(now = { Instant.now().toString() }, initialLevel = LogLevel.DEBUG)
        playbackSettings = AndroidPlaybackSettingsStore(context.getSharedPreferences("playback-settings", Context.MODE_PRIVATE))
        youtubeMediaService = AndroidYouTubeMediaService(logger, playbackSettings)
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        future.addListener({
            runCatching { future.get() }
                .onSuccess { controller ->
                    audioPlayer.value = Media3AudioPlayer(controller, scope, youtubeMediaService, logger, context.getSharedPreferences("playback-queue", Context.MODE_PRIVATE))
                }
                .onFailure { error ->
                    playerError.value = "Nie udało się połączyć z usługą odtwarzania"
                    logger.error("Playback", "controller_connect_failed", "Nie udało się połączyć z usługą odtwarzania", throwable = error)
                }
        }, ContextCompat.getMainExecutor(context))
    }
}
