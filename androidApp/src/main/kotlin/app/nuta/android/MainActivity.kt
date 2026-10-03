package app.nuta.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nuta.AppContainer
import app.nuta.data.fake.FakeSpotifyRepository
import app.nuta.domain.DataSourceSelectingRepository
import app.nuta.core.security.SecretValue
import app.nuta.resources.Res
import app.nuta.resources.playback_connect_failed
import app.nuta.resources.retry
import app.nuta.settings.DataSource
import app.nuta.spotify.SpotifyWebToken
import app.nuta.ui.NutaApp
import kotlinx.coroutines.delay
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
            if (expiry > System.currentTimeMillis() + SpotifyAndroidRepository.TokenExpiryMarginMs) {
                SpotifyWebToken(SecretValue.of(value), expiry)
            } else null
        }
        setContent {
            val player by AppServices.audioPlayer.collectAsState()
            val connectFailed by AppServices.playerConnectFailed.collectAsState()
            val activePlayer = player
            if (activePlayer == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (connectFailed) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(24.dp),
                        ) {
                            Text(stringResource(Res.string.playback_connect_failed))
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { AppServices.retryConnect(applicationContext) }) {
                                Text(stringResource(Res.string.retry))
                            }
                        }
                    } else {
                        CircularProgressIndicator()
                    }
                }
                return@setContent
            }
            val settings by playbackSettings.settings.collectAsState()
            var token by remember { mutableStateOf(restoredToken) }
            LaunchedEffect(Unit) {
                AppServices.spotifySessionInvalid.collect {
                    preferences.edit().remove("accessToken").remove("expiresAt").apply()
                    token = null
                }
            }
            // 5 min przed wygaśnięciem: cichy refresh z CookieManager; dopiero potem ekran logowania.
            val activeExpiry = token?.expiresAtMs
            LaunchedEffect(activeExpiry) {
                val expiry = activeExpiry ?: return@LaunchedEffect
                val refreshAt = expiry - TokenRefreshLeadMs
                val waitMs = (refreshAt - System.currentTimeMillis()).coerceAtLeast(0L)
                delay(waitMs)
                val refreshed = runCatching { fetchSpotifyToken(logger) }.getOrNull()
                if (refreshed != null) {
                    refreshed.value.use { value ->
                        preferences.edit().putString("accessToken", value).putLong("expiresAt", refreshed.expiresAtMs).apply()
                    }
                    token = refreshed
                    logger.info("SpotifySession", "token_refresh_ok", "Odświeżono token Spotify z cookies")
                } else {
                    preferences.edit().remove("accessToken").remove("expiresAt").apply()
                    token = null
                    logger.warn("SpotifySession", "token_refresh_failed", "Cichy refresh Spotify nieudany — logowanie")
                }
            }
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
                        activeToken?.let {
                            SpotifyAndroidRepository(
                                it,
                                logger,
                                getSharedPreferences("spotify-playlists-cache", MODE_PRIVATE),
                                onSessionInvalid = { AppServices.notifySpotifySessionInvalid() },
                            )
                        } ?: FakeSpotifyRepository(logger),
                        AppServices.listenBrainzRepository,
                    )
                }
                val container = remember(repository, activePlayer) {
                    AppContainer(
                        spotifyRepository = repository,
                        audioPlayer = activePlayer,
                        logger = logger,
                        youtubeMediaService = youtubeMediaService,
                        playbackSettings = playbackSettings,
                        networkResumed = AppServices.networkResumed,
                        onSpotifySessionInvalid = { AppServices.notifySpotifySessionInvalid() },
                        listenBrainzTokenUnreadable = AppServices.listenBrainzTokenUnreadable,
                    )
                }
                NutaApp(container)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (AppServices.audioPlayer.value == null && !AppServices.isReconnectInProgress()) {
            AppServices.retryConnect(applicationContext)
        } else {
            AppServices.notifyUiResumed()
        }
    }

    private companion object {
        const val TokenRefreshLeadMs = 5 * 60_000L
    }
}
