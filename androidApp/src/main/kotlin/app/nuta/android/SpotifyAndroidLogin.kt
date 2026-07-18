package app.nuta.android

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.nuta.core.logging.NutaLogger
import app.nuta.core.security.SecretValue
import app.nuta.spotify.SpotifyWebToken
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyAndroidLogin(
    logger: NutaLogger,
    onSessionDetected: (SpotifyWebToken) -> Unit,
) {
    var status by remember { mutableStateOf("Zaloguj się na stronie Spotify") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var tokenRequestRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val bridge = remember {
        SpotifyTokenBridge(
            onSuccess = { token -> onSessionDetected(token) },
            onStatus = { message -> status = message },
            logger = logger,
        )
    }
    val connectSession: () -> Unit = {
        if (!tokenRequestRunning) {
            tokenRequestRunning = true
            status = "Pobieranie sesji Spotify…"
            scope.launch {
                runCatching { fetchSpotifyToken(logger) }
                    .onSuccess(onSessionDetected)
                    .onFailure { error ->
                        tokenRequestRunning = false
                        status = "Nie wykryto sesji. Zaloguj się i spróbuj ponownie."
                        logger.error("SpotifyAndroidLogin", "native_token_failed", "Nie udało się pobrać sesji Spotify z cookies", throwable = error)
                    }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { webView?.destroy() }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF101418))) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF131A20)).padding(10.dp)) {
            Text(status, color = Color.White, modifier = Modifier.fillMaxWidth(0.62f).padding(10.dp))
            Button(onClick = connectSession) { Text("Połącz po zalogowaniu") }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.userAgentString = settings.userAgentString.replace("; wv", "")
                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = WebChromeClient()
                    addJavascriptInterface(bridge, "NutaAndroid")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            logger.debug("SpotifyAndroidLogin", "navigation_finished", "Zakończono nawigację WebView", fields = mapOf("url" to url.take(180)))
                            val cookies = android.webkit.CookieManager.getInstance().getCookie("https://open.spotify.com").orEmpty()
                            if (cookies.split(';').any { it.trim().startsWith("sp_dc=") }) connectSession()
                            if (url.startsWith("https://open.spotify.com")) {
                                status = "Sprawdzanie sesji Spotify…"
                                view.evaluateJavascript(TOKEN_SCRIPT, null)
                            }
                        }
                    }
                    webView = this
                    loadUrl("https://accounts.spotify.com/")
                }
            },
        )
    }
}

private suspend fun fetchSpotifyToken(logger: NutaLogger): SpotifyWebToken = withContext(Dispatchers.IO) {
    val cookies = android.webkit.CookieManager.getInstance().getCookie("https://open.spotify.com").orEmpty()
    require(cookies.split(';').any { it.trim().startsWith("sp_dc=") }) { "Brak cookie sp_dc" }
    logger.info("SpotifyAndroidLogin", "cookie_detected", "Wykryto cookie sesji Spotify")

    val serverTimeRoot = JSONObject(httpGet("https://open.spotify.com/api/server-time", cookies))
    val serverTime = serverTimeRoot.getLong("serverTime")
    val gist = JSONObject(httpGet("https://api.github.com/gists/22ed9c6ba463899e933427f7de1f0eef", null))
    val nuances = gist.getJSONObject("files").getJSONObject("nuances.json").getString("content")
    val array = org.json.JSONArray(nuances)
    var version = -1
    var secret = ""
    for (index in 0 until array.length()) {
        val item = array.getJSONObject(index)
        if (item.getInt("v") > version) {
            version = item.getInt("v")
            secret = item.getString("s")
        }
    }
    require(version >= 0 && secret.isNotBlank()) { "Brak parametrów TOTP" }
    val totp = spotifyTotp(secret, serverTime)
    val query = "reason=transport&productType=web-player&totp=${url(totp)}&totpServer=${url(totp)}&totpVer=$version"
    val response = JSONObject(httpGet("https://open.spotify.com/api/token?$query", cookies))
    val value = response.optString("accessToken")
    val expiresAt = response.optLong("accessTokenExpirationTimestampMs")
    require(!response.optBoolean("isAnonymous", true)) { "Spotify zwrócił sesję anonimową" }
    require(value.isNotBlank() && expiresAt > System.currentTimeMillis() + 60_000) { "Nieprawidłowy token Spotify" }
    logger.info("SpotifyAndroidLogin", "native_token_received", "Pobrano token Spotify natywnie")
    SpotifyWebToken(SecretValue.of(value), expiresAt)
}

private fun httpGet(url: String, cookies: String?): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/128.0 Mobile Safari/537.36")
        if (!cookies.isNullOrBlank()) connection.setRequestProperty("Cookie", cookies)
        val status = connection.responseCode
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        require(status in 200..299) { "HTTP $status: ${body.take(160)}" }
        return body
    } finally {
        connection.disconnect()
    }
}

private fun spotifyTotp(base32Secret: String, serverTime: Long): String {
    val key = decodeBase32(base32Secret)
    val counter = ByteBuffer.allocate(8).putLong(serverTime / 30).array()
    val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }.doFinal(counter)
    val offset = mac.last().toInt() and 0x0f
    val binary = ((mac[offset].toInt() and 0x7f) shl 24) or
        ((mac[offset + 1].toInt() and 0xff) shl 16) or
        ((mac[offset + 2].toInt() and 0xff) shl 8) or (mac[offset + 3].toInt() and 0xff)
    return (binary % 1_000_000).toString().padStart(6, '0')
}

private fun decodeBase32(value: String): ByteArray {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    var buffer = 0
    var bits = 0
    val result = mutableListOf<Byte>()
    value.uppercase().filterNot { it == '=' || it.isWhitespace() }.forEach { character ->
        val index = alphabet.indexOf(character)
        require(index >= 0) { "Nieprawidłowy Base32" }
        buffer = (buffer shl 5) or index
        bits += 5
        if (bits >= 8) {
            bits -= 8
            result += ((buffer shr bits) and 0xff).toByte()
        }
    }
    return result.toByteArray()
}

private fun url(value: String): String = URLEncoder.encode(value, "UTF-8")

private class SpotifyTokenBridge(
    private val onSuccess: (SpotifyWebToken) -> Unit,
    private val onStatus: (String) -> Unit,
    private val logger: NutaLogger,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun tokenResult(payload: String) {
        runCatching {
            val root = JSONObject(payload)
            val status = root.optInt("status")
            val token = root.optString("accessToken")
            val expiresAt = root.optLong("expiresAt")
            val anonymous = root.optBoolean("isAnonymous", true)
            check(status in 200..299) { "Endpoint tokenu Spotify HTTP $status" }
            check(!anonymous && token.isNotBlank() && expiresAt > System.currentTimeMillis() + 60_000) { "Brak aktywnej sesji Spotify" }
            logger.info("SpotifyAndroidLogin", "token_received", "Pobrano sesję Spotify w Android WebView")
            mainHandler.post { onSuccess(SpotifyWebToken(SecretValue.of(token), expiresAt)) }
        }.onFailure {
            logger.error("SpotifyAndroidLogin", "token_rejected", "Nie udało się pobrać sesji Spotify", throwable = it)
            mainHandler.post { onStatus("Nie wykryto sesji. Zaloguj się i spróbuj ponownie.") }
        }
    }

    @JavascriptInterface
    fun tokenError(message: String) {
        logger.warn("SpotifyAndroidLogin", "token_script_failed", "Skrypt tokenu Spotify nie powiódł się", fields = mapOf("reason" to message.take(200)))
        mainHandler.post { onStatus("Nie udało się pobrać sesji Spotify") }
    }
}

private val TOKEN_SCRIPT = """
    (async () => {
      try {
        const alphabet='ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
        const decode=v=>{let b=0,n=0,o=[];for(const c of v.toUpperCase().replace(/[=\s]/g,'')){const i=alphabet.indexOf(c);if(i<0)throw Error('base32');b=(b<<5)|i;n+=5;if(n>=8){n-=8;o.push((b>>n)&255)}}return new Uint8Array(o)};
        const st=await fetch('/api/server-time',{credentials:'include'});const serverTime=(await st.json()).serverTime;
        const gr=await fetch('https://api.github.com/gists/22ed9c6ba463899e933427f7de1f0eef');const gist=await gr.json();
        const nuances=JSON.parse(gist.files['nuances.json'].content);const nuance=nuances.reduce((a,b)=>b.v>a.v?b:a);
        const counter=new ArrayBuffer(8);new DataView(counter).setBigUint64(0,BigInt(Math.floor(serverTime/30)));
        const key=await crypto.subtle.importKey('raw',decode(nuance.s),{name:'HMAC',hash:'SHA-1'},false,['sign']);
        const d=new Uint8Array(await crypto.subtle.sign('HMAC',key,counter));const p=d[d.length-1]&15;
        const bin=((d[p]&127)<<24)|(d[p+1]<<16)|(d[p+2]<<8)|d[p+3];const totp=String((bin>>>0)%1000000).padStart(6,'0');
        const q=new URLSearchParams({reason:'transport',productType:'web-player',totp,totpServer:totp,totpVer:String(nuance.v)});
        const r=await fetch('/api/token?'+q,{credentials:'include'});const x=await r.json();
        NutaAndroid.tokenResult(JSON.stringify({status:r.status,accessToken:x.accessToken||'',expiresAt:x.accessTokenExpirationTimestampMs||0,isAnonymous:x.isAnonymous===true}));
      } catch(e) { NutaAndroid.tokenError(String(e)); }
    })();
""".trimIndent()
