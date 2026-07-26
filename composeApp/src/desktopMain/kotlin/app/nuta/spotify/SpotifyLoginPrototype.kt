package app.nuta.spotify

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.nuta.core.logging.MemoryLogger

// Zastępuje dawny ekran JCEF: logowanie realnie dzieje się w osobnym natywnym oknie
// WebView2 (native/spotify-login), które JCEF-a nie zastępuje wewnątrz Compose — patrz
// docs/WEBVIEW2_MIGRATION_PLAN.md. Ten composable tylko uruchamia helper i wylicza token.
@Composable
fun SpotifyLoginPrototype(
    logger: MemoryLogger,
    onSessionDetected: (SpotifyWebToken) -> Unit,
    onClose: () -> Unit,
) {
    var status by remember { mutableStateOf("loading") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        errorMessage = null
        status = "loading"
        runCatching {
            logger.info("SpotifyLogin", "login_helper_starting", "Uruchamianie helpera logowania WebView2")
            val spDc = SpotifyLoginHelperClient(logger).runLogin()
            if (spDc == null) {
                logger.info("SpotifyLogin", "login_cancelled", "Anulowano logowanie Spotify")
                onClose()
                return@LaunchedEffect
            }
            status = "token"
            val token = SpotifyWebTokenClient(logger).fetchToken(spDc)
            logger.info("SpotifyLogin", "web_token_received", "Odebrano prawidłowy token Spotify")
            onSessionDetected(token)
        }.onFailure { error ->
            logger.error("SpotifyLogin", "login_failed", "Logowanie Spotify nie powiodło się", throwable = error)
            errorMessage = error.message ?: "Nieznany błąd logowania"
        }
    }

    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Color(0xFF101418))) {
            if (errorMessage != null) {
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Nie udało się zalogować do Spotify", color = Color.White, style = MaterialTheme.typography.h5)
                    Spacer(Modifier.height(10.dp))
                    Text(errorMessage.orEmpty(), color = Color(0xFFABB7C0))
                    Spacer(Modifier.height(20.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { attempt += 1 }) { Text("Spróbuj ponownie") }
                        Button(onClick = onClose) { Text("Wróć do Nuta") }
                    }
                }
            } else {
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (status == "token") "Kończenie logowania…" else "Otwieranie okna logowania Spotify…",
                        color = Color.White,
                    )
                }
            }
        }
    }
}
