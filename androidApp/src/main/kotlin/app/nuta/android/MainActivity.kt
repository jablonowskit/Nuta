package app.nuta.android

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.nuta.AppContainer
import app.nuta.core.logging.LogLevel
import app.nuta.core.logging.MemoryLogger
import app.nuta.data.fake.FakeSpotifyRepository
import app.nuta.core.security.SecretValue
import app.nuta.spotify.SpotifyWebToken
import app.nuta.ui.NutaApp
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.time.Instant

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private val audioPlayerState = mutableStateOf<Media3AudioPlayer?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val logger = MemoryLogger(now = { Instant.now().toString() }, initialLevel = LogLevel.DEBUG)
        val preferences = getSharedPreferences("spotify-session", MODE_PRIVATE)
        val playbackSettings = AndroidPlaybackSettingsStore(getSharedPreferences("playback-settings", MODE_PRIVATE))
        val restoredToken = preferences.getString("accessToken", null)?.let { value ->
            val expiry = preferences.getLong("expiresAt", 0L)
            if (expiry > System.currentTimeMillis() + 60_000) SpotifyWebToken(SecretValue.of(value), expiry) else null
        }
        val youtubeMediaService = AndroidYouTubeMediaService(logger, playbackSettings)
        val queuePreferences = getSharedPreferences("playback-queue", MODE_PRIVATE)

        val sessionToken = SessionToken(applicationContext, ComponentName(applicationContext, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(applicationContext, sessionToken).buildAsync()
        controllerFuture.addListener(
            { audioPlayerState.value = Media3AudioPlayer(controllerFuture.get(), scope, youtubeMediaService, logger, queuePreferences) },
            ContextCompat.getMainExecutor(this),
        )

        setContent {
            val audioPlayer by audioPlayerState
            val player = audioPlayer
            if (player == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@setContent
            }
            var token by remember { mutableStateOf(restoredToken) }
            var showLogin by remember { mutableStateOf(restoredToken == null) }
            if (showLogin) {
                SpotifyAndroidLogin(logger) { session ->
                    session.value.use { value ->
                        preferences.edit().putString("accessToken", value).putLong("expiresAt", session.expiresAtMs).apply()
                    }
                    token = session
                    showLogin = false
                }
            } else {
                val activeToken = token
                val repository = remember(activeToken) {
                    activeToken?.let { SpotifyAndroidRepository(it, logger, getSharedPreferences("spotify-playlists-cache", MODE_PRIVATE)) } ?: FakeSpotifyRepository(logger)
                }
                val container = remember(repository) {
                    AppContainer(spotifyRepository = repository, audioPlayer = player, logger = logger, youtubeMediaService = youtubeMediaService, playbackSettings = playbackSettings)
                }
                NutaApp(container, onSpotifyLogin = { showLogin = true })
            }
        }
    }

    override fun onDestroy() {
        MediaController.releaseFuture(controllerFuture)
        scope.cancel()
        super.onDestroy()
    }
}
