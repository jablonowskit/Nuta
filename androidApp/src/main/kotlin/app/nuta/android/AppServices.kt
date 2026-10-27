package app.nuta.android

import app.nuta.update.AppUpdater
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.nuta.core.logging.LogLevel
import app.nuta.core.logging.MemoryLogger
import app.nuta.listenbrainz.ListenBrainzRepository
import app.nuta.listenbrainz.ListenBrainzScrobbler
import app.nuta.musicbrainz.MusicBrainzRepository
import app.nuta.domain.SpotifyRepository
import app.nuta.player.QueueContinuation
import app.nuta.player.SleepTimer
import app.nuta.ui.initPlatformBrowser
import app.nuta.youtube.SourceSelectingMediaService
import app.nuta.youtube.YouTubeMediaService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
    @Volatile private var reconnectJob: Job? = null
    /** True od startu buildAsync do sukcesu/porażki — blokuje równoległy drugi connect. */
    @Volatile private var connecting = false
    @Volatile private var appContext: Context? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    lateinit var logger: MemoryLogger
        private set
    lateinit var playbackSettings: AndroidPlaybackSettingsStore
        private set
    /** Dla Settings: uszkodzony blob tokenu ListenBrainz — czytaj świeżo, nie cache'uj w Compose. */
    val listenBrainzTokenUnreadable: Boolean
        get() = if (::playbackSettings.isInitialized) playbackSettings.listenBrainzTokenUnreadable else false
    lateinit var youtubeMediaService: YouTubeMediaService
        private set
    lateinit var listenBrainzRepository: ListenBrainzRepository
        private set

    val audioPlayer = MutableStateFlow<Media3AudioPlayer?>(null)
    val playerConnectFailed = MutableStateFlow(false)
    /** Przeżywa przebudowy AppContainer w MainActivity; player zawsze aktualny po reconnect. */
    val sleepTimer = SleepTimer(player = { audioPlayer.value })
    /** Jeden na proces, żeby stan pobierania przetrwał przebudowę kontenera i obrót ekranu. */
    val appUpdater = AppUpdater(install = { url, onProgress ->
        ApkInstaller.install(checkNotNull(appContext) { "AppServices nie wystartował" }, url, onProgress)
    })
    /** Repozytorium bieżącego kontenera UI (zmienia się z sesją Spotify) — dla [QueueContinuation]. */
    @Volatile var repository: SpotifyRepository? = null
    lateinit var queueContinuation: QueueContinuation
        private set
    /** Emitowane przy powrocie sieci — UI odświeża listy (App.kt). Nie przy każdym onResume. */
    private val _networkResumed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val networkResumed: SharedFlow<Unit> = _networkResumed.asSharedFlow()
    /** Sesja Spotify unieważniona (401/403) — MainActivity zeruje token. */
    private val _spotifySessionInvalid = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val spotifySessionInvalid: SharedFlow<Unit> = _spotifySessionInvalid.asSharedFlow()

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        initPlatformBrowser(context)
        logger = MemoryLogger(now = { Instant.now().toString() }, initialLevel = LogLevel.DEBUG, jsonSink = { line -> Log.d("NutaLog", line) })
        val credentials = AndroidCredentialStore(context.getSharedPreferences("credentials-encrypted", Context.MODE_PRIVATE))
        playbackSettings = AndroidPlaybackSettingsStore(
            context.getSharedPreferences("playback-settings", Context.MODE_PRIVATE),
            credentials,
        )
        val youTube = AndroidYouTubeMediaService(logger, playbackSettings, context.applicationContext)
        val soundCloud = AndroidSoundCloudMediaService(logger, playbackSettings)
        youtubeMediaService = SourceSelectingMediaService(playbackSettings, youTube, soundCloud, logger)
        val likedCache = SharedPreferencesLikedTracksCache(
            context.getSharedPreferences("listenbrainz-liked-cache", Context.MODE_PRIVATE),
        )
        listenBrainzRepository = ListenBrainzRepository(
            playbackSettings,
            MusicBrainzRepository(logger),
            logger,
            likedCache,
        )
        queueContinuation = QueueContinuation(playbackSettings, { repository ?: listenBrainzRepository }, logger).also {
            it.attach(audioPlayer, scope)
        }
        registerNetworkCallback(context.applicationContext)
        connectController(context.applicationContext, attempt = 0)
    }

    /**
     * Usługa świadomie się zamyka (apka zamknięta z ostatnich przy pauzie). Ten kontroler
     * trzymał ją przy życiu — stopSelf() nie niszczy usługi, dopóki ktoś jest do niej podpięty,
     * a onDisconnected od razu łączyło ponownie. Do następnego onResume nie wracamy.
     */
    @Volatile private var shutDown = false

    fun releaseForShutdown() {
        shutDown = true
        reconnectJob?.cancel()
        reconnectJob = null
        clearPlayer(releaseController = true)
    }

    /** Ręczne ponowienie po trwałym błędzie connect albo z onResume / NetworkCallback. */
    fun retryConnect(context: Context? = null) {
        val ctx = context?.applicationContext ?: appContext ?: return
        shutDown = false
        if (!started) {
            start(ctx)
            return
        }
        if (audioPlayer.value != null) return
        if (isConnectInFlight()) return
        playerConnectFailed.value = false
        logger.info("Playback", "retry_connect", "Ponawiam połączenie z usługą odtwarzania")
        scheduleReconnect(ctx, attempt = 0)
    }

    fun notifySpotifySessionInvalid() {
        _spotifySessionInvalid.tryEmit(Unit)
    }

    fun isReconnectInProgress(): Boolean = isConnectInFlight()

    private fun isConnectInFlight(): Boolean =
        connecting || reconnectJob?.isActive == true

    /**
     * Łączy się z usługą odtwarzania i — co najważniejsze — obsługuje rozłączenie. Wcześniej nic
     * go nie wykrywało: po ubiciu usługi przez system `audioPlayer` wskazywał na martwy kontroler,
     * a `started = true` blokowało ponowną inicjalizację, więc play/seek szły w próżnię, a UI
     * pokazywało stan sprzed rozłączenia — bez żadnego komunikatu. Po rozłączeniu zwalniamy
     * kontroler i łączymy się ponownie, żeby odtwarzanie dało się wznowić bez restartu apki.
     */
    private fun connectController(context: Context, attempt: Int) {
        if (connecting) return
        connecting = true
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    logger.warn("Playback", "controller_disconnected", "Usługa odtwarzania rozłączona — łączę ponownie")
                    // Kontroler już się rozłącza — wrapper nie może wołać controller.release() drugi raz.
                    clearPlayer(releaseController = false)
                    if (!shutDown) scheduleReconnect(context, attempt = 0)
                }
            })
            .buildAsync()
        future.addListener({
            runCatching { future.get() }
                .onSuccess { controller ->
                    playerConnectFailed.value = false
                    reconnectJob?.cancel()
                    reconnectJob = null
                    // Najpierw zwolnij poprzedni wrapper+kontroler, dopiero potem buduj nowy
                    // (inaczej previous.release() czyści bridge ustawiony w init nowego playera).
                    clearPlayer(releaseController = true)
                    val player = Media3AudioPlayer(
                        controller,
                        scope,
                        youtubeMediaService,
                        logger,
                        context.getSharedPreferences("playback-queue", Context.MODE_PRIVATE),
                        playbackSettings,
                    )
                    audioPlayer.value = player
                    connecting = false
                    scrobblerScope?.cancel()
                    val attachScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                    scrobblerScope = attachScope
                    ListenBrainzScrobbler(playbackSettings, logger).attach(player, attachScope)
                }
                .onFailure { error ->
                    connecting = false
                    logger.error("Playback", "controller_connect_failed", "Nie udało się połączyć z usługą odtwarzania", throwable = error)
                    if (attempt < MAX_CONNECT_ATTEMPTS - 1) {
                        scheduleReconnect(context, attempt + 1)
                    } else {
                        playerConnectFailed.value = true
                    }
                }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun clearPlayer(releaseController: Boolean) {
        val previous = audioPlayer.value
        audioPlayer.value = null
        previous?.release(releaseController = releaseController)
    }

    @Synchronized
    private fun scheduleReconnect(context: Context, attempt: Int) {
        if (reconnectJob?.isActive == true || connecting) return
        reconnectJob = scope.launch {
            if (attempt > 0) delay((CONNECT_RETRY_BASE_MS * (1L shl (attempt - 1))).coerceAtMost(CONNECT_RETRY_MAX_MS))
            connectController(context, attempt)
            reconnectJob = null
        }
    }

    private fun registerNetworkCallback(context: Context) {
        if (networkCallback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                logger.info("Playback", "network_available", "Sieć dostępna — sygnał odświeżenia")
                _networkResumed.tryEmit(Unit)
                if (audioPlayer.value == null && !shutDown) retryConnect(context)
            }
        }
        networkCallback = callback
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onFailure { logger.warn("Playback", "network_callback_failed", "Nie zarejestrowano NetworkCallback", fields = mapOf("reason" to (it.message ?: "unknown"))) }
    }

    private const val MAX_CONNECT_ATTEMPTS = 5
    private const val CONNECT_RETRY_BASE_MS = 500L
    private const val CONNECT_RETRY_MAX_MS = 8_000L
}
