package app.nuta.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.nuta.AppContainer
import app.nuta.core.logging.LogLevel
import app.nuta.core.logging.MemoryLogger
import app.nuta.data.fake.FakeAudioPlayer
import app.nuta.data.fake.FakeSpotifyRepository
import app.nuta.core.security.SecretValue
import app.nuta.spotify.SpotifyWebToken
import app.nuta.ui.NutaApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.time.Instant

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val logger = MemoryLogger(now = { Instant.now().toString() }, initialLevel = LogLevel.DEBUG)
        val preferences = getSharedPreferences("spotify-session", MODE_PRIVATE)
        val restoredToken = preferences.getString("accessToken", null)?.let { value ->
            val expiry = preferences.getLong("expiresAt", 0L)
            if (expiry > System.currentTimeMillis() + 60_000) SpotifyWebToken(SecretValue.of(value), expiry) else null
        }
        val audioPlayer = FakeAudioPlayer(scope, logger)
        setContent {
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
                    activeToken?.let { SpotifyAndroidRepository(it, logger) } ?: FakeSpotifyRepository(logger)
                }
                val container = remember(repository) {
                    AppContainer(spotifyRepository = repository, audioPlayer = audioPlayer, logger = logger)
                }
                NutaApp(container, onSpotifyLogin = { showLogin = true })
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
