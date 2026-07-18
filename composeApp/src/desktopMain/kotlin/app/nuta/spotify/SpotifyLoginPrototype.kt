package app.nuta.spotify

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import app.nuta.core.security.SecretValue
import com.multiplatform.webview.web.LoadingState
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.rememberWebViewNavigator
import com.multiplatform.webview.web.rememberWebViewState
import dev.datlag.kcef.KCEF
import org.cef.callback.CefCompletionCallback
import org.cef.network.CefCookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.util.Base64

private const val SpotifyLoginUrl = "https://accounts.spotify.com/"
private const val SpotifySessionUrl = "https://open.spotify.com/"

@Composable
fun SpotifyLoginPrototype(
    logger: MemoryLogger,
    onSessionDetected: (SpotifyWebToken) -> Unit,
    onClose: () -> Unit,
) {
    var initialized by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var restartRequired by remember { mutableStateOf(false) }
    var initializationError by remember { mutableStateOf<String?>(null) }
    var sessionDetected by remember { mutableStateOf(false) }
    var sessionRestoreFinished by remember { mutableStateOf(false) }
    val cookieSessionStore = remember { SpotifyCookieSessionStore(logger) }
    val profileDirectory = remember {
        File(System.getenv("NUTA_WEBVIEW_DIR") ?: File(System.getProperty("java.io.tmpdir"), "nuta-spotify-webview").absolutePath)
    }
    val runtimeDirectory = remember {
        File(System.getenv("NUTA_WEBVIEW_RUNTIME_DIR") ?: File(System.getProperty("java.io.tmpdir"), "nuta-kcef-runtime").absolutePath)
    }

    LaunchedEffect(Unit) {
        logger.info("SpotifyLogin", "login_browser_initializing", "Inicjalizacja przeglądarki logowania")
        withContext(Dispatchers.IO) {
            KCEF.init(
                builder = {
                    installDir(runtimeDirectory)
                    // Spotify's login challenge uses cross-site cookies between
                    // accounts.spotify.com and challenge.spotify.com.
                    addArgs("--disable-features=BlockThirdPartyCookies")
                    progress {
                        onDownloading { progress = it.coerceAtLeast(0f) }
                        onInitialized { initialized = true }
                    }
                    download {
                        github { release("jbr-release-17.0.12b1207.37") }
                    }
                    settings {
                        cachePath = File(profileDirectory, "cache").absolutePath
                        persistSessionCookies = true
                    }
                },
                onError = { error ->
                    initializationError = error?.javaClass?.simpleName ?: "KcefInitializationError"
                    logger.error("SpotifyLogin", "login_browser_failed", "Nie udało się uruchomić przeglądarki logowania", throwable = error)
                },
                onRestartRequired = { restartRequired = true },
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { KCEF.disposeBlocking() }
            logger.info("SpotifyLogin", "login_browser_closed", "Zamknięto przeglądarkę logowania")
        }
    }

    LaunchedEffect(initialized) {
        if (initialized && !sessionRestoreFinished) {
            withContext(Dispatchers.IO) { cookieSessionStore.restore() }
            sessionRestoreFinished = true
        }
    }

    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Color(0xFF101418))) {
            when {
                initializationError != null -> PrototypeMessage(
                    title = "Nie udało się uruchomić WebView",
                    details = "Kod: $initializationError",
                    onClose = onClose,
                )
                restartRequired -> PrototypeMessage(
                    title = "WebView wymaga ponownego uruchomienia",
                    details = "Zamknij aplikację i uruchom ją ponownie.",
                    onClose = onClose,
                )
                !initialized || !sessionRestoreFinished -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Przygotowywanie bezpiecznego WebView…", color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = progress / 100f, modifier = Modifier.fillMaxWidth(0.5f))
                    Text("${progress.toInt()}%", color = Color(0xFF9AA7B0))
                }
                sessionDetected -> PrototypeMessage(
                    title = "Sesja Spotify została wykryta",
                    details = "Token web-playera został przekazany bezpiecznie do Nuta.",
                    onClose = onClose,
                )
                else -> SpotifyWebView(
                    logger = logger,
                    cookieSessionStore = cookieSessionStore,
                    onTokenDetected = { token ->
                        sessionDetected = true
                        onSessionDetected(token)
                    },
                    onClose = onClose,
                )
            }
        }
    }
}

@Composable
private fun SpotifyWebView(
    logger: MemoryLogger,
    cookieSessionStore: SpotifyCookieSessionStore,
    onTokenDetected: (SpotifyWebToken) -> Unit,
    onClose: () -> Unit,
) {
    val state = rememberWebViewState(SpotifySessionUrl)
    val navigator = rememberWebViewNavigator()
    var status by remember { mutableStateOf("Sprawdzanie zapisanej sesji Spotify…") }
    var tokenRequestStarted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        logger.info("SpotifyLogin", "login_browser_opened", "Otwarto stronę logowania Spotify")
    }

    LaunchedEffect(state.lastLoadedUrl) {
        val url = state.lastLoadedUrl ?: return@LaunchedEffect
        val uri = runCatching { URI(url) }.getOrNull()
        val allowed = runCatching {
            val host = uri?.host?.lowercase() ?: return@runCatching false
            host == "spotify.com" || host.endsWith(".spotify.com") || host == "spotifycdn.com" || host.endsWith(".spotifycdn.com")
        }.getOrDefault(false)
        if (!allowed) {
            logger.warn("SpotifyLogin", "login_domain_rejected", "Zablokowano nawigację poza domeny Spotify", fields = mapOf("hostAllowed" to "false"))
            navigator.stopLoading()
            navigator.loadUrl(SpotifyLoginUrl)
        } else if (uri?.host.equals("accounts.spotify.com", ignoreCase = true) && uri?.path?.endsWith("/status") == true) {
            logger.info("SpotifyLogin", "web_session_initializing", "Inicjalizacja sesji Spotify Web")
            navigator.loadUrl(SpotifySessionUrl)
        } else if (uri?.host.equals("open.spotify.com", ignoreCase = true) && !tokenRequestStarted) {
            tokenRequestStarted = true
            status = "Pobieranie sesji Spotify…"
            delay(3_000)
            runCatching {
                logger.info("SpotifyLogin", "web_token_request_started", "Rozpoczęto pobieranie tokenu w WebView")
                evaluateJavaScript(
                    navigator,
                    """
                    window.__nutaTokenResult = '';
                    (async () => {
                      const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
                      const decodeBase32 = value => {
                        let buffer = 0, bits = 0, output = [];
                        for (const char of value.toUpperCase().replace(/[=\s]/g, '')) {
                          const index = alphabet.indexOf(char);
                          if (index < 0) throw new Error('invalid_base32');
                          buffer = (buffer << 5) | index;
                          bits += 5;
                          if (bits >= 8) { bits -= 8; output.push((buffer >> bits) & 255); }
                        }
                        return new Uint8Array(output);
                      };
                      const serverTime = (await (await fetch('/api/server-time')).json()).serverTime;
                      const gist = await (await fetch('https://api.github.com/gists/22ed9c6ba463899e933427f7de1f0eef')).json();
                      const nuances = JSON.parse(gist.files['nuances.json'].content);
                      const nuance = nuances.reduce((latest, item) => item.v > latest.v ? item : latest);
                      const counter = new ArrayBuffer(8);
                      new DataView(counter).setBigUint64(0, BigInt(Math.floor(serverTime / 30)));
                      const key = await crypto.subtle.importKey('raw', decodeBase32(nuance.s), { name: 'HMAC', hash: 'SHA-1' }, false, ['sign']);
                      const digest = new Uint8Array(await crypto.subtle.sign('HMAC', key, counter));
                      const offset = digest[digest.length - 1] & 15;
                      const binary = ((digest[offset] & 127) << 24) | (digest[offset + 1] << 16) | (digest[offset + 2] << 8) | digest[offset + 3];
                      const totp = String((binary >>> 0) % 1000000).padStart(6, '0');
                      const params = new URLSearchParams({ reason: 'transport', productType: 'web-player', totp, totpServer: totp, totpVer: String(nuance.v) });
                      const response = await fetch('/api/token?' + params, { credentials: 'include' });
                        const payload = await response.json();
                        const result = JSON.stringify({
                          status: response.status,
                          accessToken: payload.accessToken || '',
                          expiresAt: payload.accessTokenExpirationTimestampMs || 0,
                          isAnonymous: payload.isAnonymous === true
                        });
                        window.__nutaTokenResult = btoa(unescape(encodeURIComponent(result)));
                    })().catch(() => { window.__nutaTokenResult = 'ERROR'; });
                    return 'STARTED';
                    """.trimIndent(),
                )
                val encoded = waitForTokenResult(navigator)
                require(encoded != "ERROR") { "Żądanie tokenu w WebView nie powiodło się" }
                val payload = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
                val root = Json.parseToJsonElement(payload).jsonObject
                val statusCode = root.getValue("status").jsonPrimitive.content.toInt()
                require(statusCode in 200..299) { "Endpoint tokenu zwrócił HTTP $statusCode" }
                val value = root["accessToken"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val expiresAt = root["expiresAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                val isAnonymous = root["isAnonymous"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                if (isAnonymous) {
                    tokenRequestStarted = false
                    status = "Zaloguj się na stronie Spotify"
                    logger.info("SpotifyLogin", "persisted_session_missing", "Brak aktywnej zapisanej sesji Spotify")
                    navigator.loadUrl(SpotifyLoginUrl)
                    return@runCatching
                }
                require(value.isNotBlank() && expiresAt > System.currentTimeMillis() + 60_000) { "Nieprawidłowy token Spotify" }
                logger.info("SpotifyLogin", "web_token_received", "Odebrano prawidłowy token z WebView")
                flushCookies(logger)
                withContext(Dispatchers.IO) {
                    runCatching { cookieSessionStore.save() }.onFailure { error ->
                        logger.warn("SpotifySession", "cookie_store_unavailable", "Eksport cookies JCEF jest niedostępny; używany będzie testowy magazyn tokenu", fields = mapOf("reason" to (error.javaClass.simpleName ?: "unknown")))
                    }
                }
                onTokenDetected(SpotifyWebToken(SecretValue.of(value), expiresAt))
            }.onFailure { error ->
                tokenRequestStarted = false
                status = "Nie udało się pobrać sesji. Spróbuj ponownie."
                logger.error("SpotifyLogin", "web_token_rejected", "Nie udało się odebrać tokenu z WebView", throwable = error)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF131A20)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = {
                logger.info("SpotifyLogin", "login_cancelled", "Anulowano logowanie Spotify")
                onClose()
            }) { Text("Anuluj") }
            Text(status, color = Color.White, modifier = Modifier.weight(1f))
            if (state.loadingState is LoadingState.Loading) CircularProgressIndicator(Modifier.height(24.dp))
        }
        WebView(state = state, navigator = navigator, modifier = Modifier.fillMaxSize())
    }
}

private suspend fun flushCookies(logger: MemoryLogger) {
    logger.debug("SpotifyLogin", "cookie_flush_started", "Rozpoczęto zapis magazynu cookies WebView")
    val completed = CompletableDeferred<Unit>()
    val accepted = CefCookieManager.getGlobalManager().flushStore(
        CefCompletionCallback { completed.complete(Unit) },
    )
    require(accepted) { "JCEF odrzucił zapis magazynu cookies" }
    withTimeout(10_000) { completed.await() }
    logger.info("SpotifyLogin", "cookie_flush_completed", "Zapisano magazyn cookies WebView")
}

private suspend fun evaluateJavaScript(
    navigator: com.multiplatform.webview.web.WebViewNavigator,
    script: String,
): String = withTimeout(5_000) {
    CompletableDeferred<String>().also { result ->
        navigator.evaluateJavaScript(script) { value -> result.complete(value) }
    }.await()
}

private suspend fun waitForTokenResult(
    navigator: com.multiplatform.webview.web.WebViewNavigator,
): String = withTimeout(20_000) {
    while (true) {
        val result = evaluateJavaScript(navigator, "window.__nutaTokenResult || ''")
        if (result.isNotBlank()) return@withTimeout result
        delay(500)
    }
    error("Nieosiągalne")
}

@Composable
private fun PrototypeMessage(title: String, details: String, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = Color.White, style = MaterialTheme.typography.h5)
        Spacer(Modifier.height(10.dp))
        Text(details, color = Color(0xFFABB7C0))
        Spacer(Modifier.height(20.dp))
        Button(onClick = onClose) { Text("Wróć do Nuta") }
    }
}
