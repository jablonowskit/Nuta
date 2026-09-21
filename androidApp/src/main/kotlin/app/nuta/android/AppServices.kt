package app.nuta.android

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.nuta.core.logging.LogLevel
import app.nuta.core.logging.MemoryLogger
import app.nuta.listenbrainz.ListenBrainzRepository
import app.nuta.listenbrainz.ListenBrainzScrobbler
import app.nuta.musicbrainz.MusicBrainzRepository
import app.nuta.ui.initPlatformBrowser
import app.nuta.youtube.SourceSelectingMediaService
import app.nuta.youtube.YouTubeMediaService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

/**
 * Usługi o czasie życia procesu. Player i MediaController nie mogą należeć do Activity:
 * serwis odtwarzania (i kontrolki na ekranie blokady) żyją dłużej niż UI.
 */
object AppServices {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @Volatile private var started = false
    /** Anulowany i tworzony od nowa przy każdym połączeniu — patrz connectController. */
    @Volatile private var scrobblerScope: CoroutineScope? = null

    lateinit var logger: MemoryLogger
        private set
    lateinit var playbackSettings: AndroidPlaybackSettingsStore
        private set
    lateinit var youtubeMediaService: YouTubeMediaService
        private set
    lateinit var listenBrainzRepository: ListenBrainzRepository
        private set

    val audioPlayer = MutableStateFlow<Media3AudioPlayer?>(null)
    val playerConnectFailed = MutableStateFlow(false)

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        initPlatformBrowser(context)
        logger = MemoryLogger(now = { Instant.now().toString() }, initialLevel = LogLevel.DEBUG, jsonSink = { line -> Log.d("NutaLog", line) })
        playbackSettings = AndroidPlaybackSettingsStore(context.getSharedPreferences("playback-settings", Context.MODE_PRIVATE))
        val youTube = AndroidYouTubeMediaService(logger, playbackSettings, context.applicationContext)
        val soundCloud = AndroidSoundCloudMediaService(logger, playbackSettings)
        youtubeMediaService = SourceSelectingMediaService(playbackSettings, youTube, soundCloud, logger)
        listenBrainzRepository = ListenBrainzRepository(playbackSettings, MusicBrainzRepository(logger), logger)
        connectController(context.applicationContext)
    }

    /**
     * Łączy się z usługą odtwarzania i — co najważniejsze — obsługuje rozłączenie. Wcześniej nic
     * go nie wykrywało: po ubiciu usługi przez system `audioPlayer` wskazywał na martwy kontroler,
     * a `started = true` blokowało ponowną inicjalizację, więc play/seek szły w próżnię, a UI
     * pokazywało stan sprzed rozłączenia — bez żadnego komunikatu. Po rozłączeniu zwalniamy
     * kontroler i łączymy się ponownie, żeby odtwarzanie dało się wznowić bez restartu apki.
     */
    private fun connectController(context: Context) {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    logger.warn("Playback", "controller_disconnected", "Usługa odtwarzania rozłączona — łączę ponownie")
                    controller.release()
                    audioPlayer.value = null
                    connectController(context)
                }
            })
            .buildAsync()
        future.addListener({
            runCatching { future.get() }
                .onSuccess { controller ->
                    playerConnectFailed.value = false
                    val player = Media3AudioPlayer(controller, scope, youtubeMediaService, logger, context.getSharedPreferences("playback-queue", Context.MODE_PRIVATE), playbackSettings)
                    audioPlayer.value = player
                    // Scrobbler sam sprawdza dataSource przy każdym utworze, więc podłączamy go
                    // raz, do skope'u procesu — niezależnie od aktualnie wybranego źródła danych.
                    // Własny scope per połączenie: po rozłączeniu i ponownym podpięciu stary
                    // scrobbler musi zniknąć, inaczej każde odsłuchanie poszłoby zgłoszone tyle
                    // razy, ile było połączeń.
                    scrobblerScope?.cancel()
                    val attachScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                    scrobblerScope = attachScope
                    ListenBrainzScrobbler(playbackSettings, logger).attach(player, attachScope)
                }
                .onFailure { error ->
                    playerConnectFailed.value = true
                    logger.error("Playback", "controller_connect_failed", "Nie udało się połączyć z usługą odtwarzania", throwable = error)
                }
        }, ContextCompat.getMainExecutor(context))
    }
}
