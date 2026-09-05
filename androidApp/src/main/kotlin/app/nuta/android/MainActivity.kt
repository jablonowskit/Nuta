package app.nuta.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.nuta.AppContainer
import app.nuta.data.fake.FakeSpotifyRepository
import app.nuta.domain.DataSourceSelectingRepository
import app.nuta.core.security.SecretValue
import app.nuta.resources.Res
import app.nuta.resources.playback_connect_failed
import app.nuta.settings.DataSource
import app.nuta.spotify.SpotifyWebToken
import app.nuta.ui.NutaApp
import org.jetbrains.compose.resources.stringResource

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppServices.start(applicationContext)
        val logger = AppServices.logger
        val playbackSettings = AppServices.playbackSettings
        val youtubeMediaService = AppServices.youtubeMediaService
        val preferences = getSharedPreferences("spotify-session", MODE_PRIVATE)
        val restoredToken = preferences.getString("accessToken", null)?.let { value ->
            val expiry = preferences.getLong("expiresAt", 0L)
            if (expiry > System.currentTimeMillis() + 60_000) SpotifyWebToken(SecretValue.of(value), expiry) else null
        }
        setContent {
            val player by AppServices.audioPlayer.collectAsState()
            val connectFailed by AppServices.playerConnectFailed.collectAsState()
            val activePlayer = player
            if (activePlayer == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (connectFailed) Text(stringResource(Res.string.playback_connect_failed)) else CircularProgressIndicator()
                }
                return@setContent
            }
            val settings by playbackSettings.settings.collectAsState()
            var token by remember { mutableStateOf(restoredToken) }
            // Logowanie do Spotify ma sens tylko wtedy, gdy DataSource faktycznie wskazuje na
            // Spotify — bez tego warunku apka żądała logowania nawet w trybie ListenBrainz,
            // gdzie SpotifyAndroidRepository i tak nigdy nie jest używane (patrz
            // DataSourceSelectingRepository niżej).
            if (settings.dataSource == DataSource.SPOTIFY && token == null) {
                SpotifyAndroidLogin(logger) { session ->
                    session.value.use { value ->
                        preferences.edit().putString("accessToken", value).putLong("expiresAt", session.expiresAtMs).apply()
                    }
                    token = session
                }
            } else {
                val activeToken = token
                val repository = remember(activeToken) {
                    DataSourceSelectingRepository(
                        playbackSettings,
                        activeToken?.let { SpotifyAndroidRepository(it, logger, getSharedPreferences("spotify-playlists-cache", MODE_PRIVATE)) } ?: FakeSpotifyRepository(logger),
                        AppServices.listenBrainzRepository,
                    )
                }
                val container = remember(repository) {
                    AppContainer(spotifyRepository = repository, audioPlayer = activePlayer, logger = logger, youtubeMediaService = youtubeMediaService, playbackSettings = playbackSettings)
                }
                NutaApp(container)
            }
        }
    }
}
