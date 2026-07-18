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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.nuta.core.logging.NutaLogger
import app.nuta.core.security.SecretValue
import app.nuta.spotify.SpotifyWebToken
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyAndroidLogin(
    logger: NutaLogger,
    onSessionDetected: (SpotifyWebToken) -> Unit,
) {
    var status by remember { mutableStateOf("Zaloguj się na stronie Spotify") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val bridge = remember {
        SpotifyTokenBridge(
            onSuccess = { token -> onSessionDetected(token) },
            onStatus = { message -> status = message },
            logger = logger,
        )
    }

    DisposableEffect(Unit) {
        onDispose { webView?.destroy() }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF101418))) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF131A20)).padding(10.dp)) {
            Text(status, color = Color.White, modifier = Modifier.fillMaxWidth(0.62f).padding(10.dp))
            Button(onClick = {
                status = "Pobieranie sesji Spotify…"
                webView?.loadUrl("https://open.spotify.com/")
            }) { Text("Połącz po zalogowaniu") }
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
