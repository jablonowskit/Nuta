package app.nuta

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.nuta.core.logging.LogLevel
import app.nuta.core.logging.MemoryLogger
import app.nuta.data.fake.FakeSpotifyRepository
import app.nuta.player.MpvAudioPlayer
import app.nuta.platform.RotatingJsonLogSink
import app.nuta.spotify.SpotifyLoginPrototype
import app.nuta.spotify.SpotifyTestTokenStore
import app.nuta.spotify.SpotifyWebSearchRepository
import app.nuta.youtube.NutaYouTubeMediaService
import app.nuta.ui.NutaApp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.plus
import java.time.Instant

fun main() {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val sink = RotatingJsonLogSink()
    val configuredLevel = runCatching { LogLevel.valueOf(System.getenv("NUTA_LOG_LEVEL") ?: "DEBUG") }.getOrDefault(LogLevel.DEBUG)
    val logger = MemoryLogger(
        now = { Instant.now().toString() },
        initialLevel = configuredLevel,
        jsonSink = { line -> println(line); sink.write(line) },
    )
    val youtubeMediaService = NutaYouTubeMediaService(logger)
    val audioPlayer = MpvAudioPlayer(scope, youtubeMediaService, logger)
    val tokenStore = SpotifyTestTokenStore(logger)
    val restoredToken = tokenStore.load()
    val container = AppContainer(
        spotifyRepository = restoredToken?.let { SpotifyWebSearchRepository(it, logger) } ?: FakeSpotifyRepository(logger),
        audioPlayer = audioPlayer,
        logger = logger,
        youtubeMediaService = youtubeMediaService,
    )

    application {
        Window(
            onCloseRequest = {
                logger.info("Application", "app_stopped", "Zamknięto Nuta Linux GUI")
                scope.cancel()
                exitApplication()
            },
            title = "Nuta — Linux GUI",
            state = rememberWindowState(width = 1280.dp, height = 760.dp),
        ) {
            // Przy starcie próbujemy odtworzyć sesję utrwaloną w profilu WebView.
            var showSpotifyLogin by remember { mutableStateOf(restoredToken == null) }
            var activeContainer by remember { mutableStateOf(container) }
            var tokenExpiresAtMs by remember { mutableStateOf(restoredToken?.expiresAtMs) }
            var refreshingSession by remember { mutableStateOf(false) }

            LaunchedEffect(tokenExpiresAtMs) {
                val expiry = tokenExpiresAtMs ?: return@LaunchedEffect
                val refreshAt = expiry - 5 * 60_000L
                val waitMs = (refreshAt - System.currentTimeMillis()).coerceAtLeast(0L)
                delay(waitMs)
                refreshingSession = true
                logger.info(
                    "SpotifySession",
                    "token_refresh_started",
                    "Rozpoczęto automatyczne odświeżanie tokenu Spotify",
                    fields = mapOf("trigger" to "expiry_margin"),
                )
                showSpotifyLogin = true
            }
            if (showSpotifyLogin) {
                SpotifyLoginPrototype(
                    logger = logger,
                    onSessionDetected = { token ->
                        tokenStore.save(token)
                        activeContainer = AppContainer(
                            spotifyRepository = SpotifyWebSearchRepository(token, logger),
                            audioPlayer = container.audioPlayer,
                            logger = logger,
                            youtubeMediaService = container.youtubeMediaService,
                        )
                        tokenExpiresAtMs = token.expiresAtMs
                        showSpotifyLogin = false
                        if (refreshingSession) {
                            refreshingSession = false
                            logger.info("SpotifySession", "token_refresh_completed", "Odświeżono token Spotify")
                        } else {
                            logger.info("SpotifySession", "session_activated", "Aktywowano sesję Spotify dla wyszukiwania")
                        }
                    },
                    onClose = {
                        showSpotifyLogin = false
                        if (refreshingSession) {
                            refreshingSession = false
                            logger.warn("SpotifySession", "token_refresh_cancelled", "Anulowano odświeżanie tokenu Spotify")
                        }
                    },
                )
            } else {
                NutaApp(activeContainer, onSpotifyLogin = { showSpotifyLogin = true })
            }
        }
    }
}
