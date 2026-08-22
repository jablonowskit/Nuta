package app.nuta.android

import android.content.Context
import android.webkit.WebView
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Rozwiązuje wyzwania YouTube "n" (throttling strumienia) i "sig" (signatureCipher klienta WEB)
 * uruchamiając oficjalny, utrzymywany przez community solver JS z github.com/yt-dlp/ejs w
 * headless Android WebView — ten sam skompilowany bundle, z którego korzysta biblioteka
 * youtube_explode_dart (używana przez Spotube), tylko wykonywany w WebView zamiast w procesie
 * Deno. Bundle jest czysto przeglądarkowy — bez zależności od Deno/Node (zweryfikowane wprost
 * w źródle: zero wystąpień `Deno.`/`require(`/`process.`, a sam dosamopolyfilluje
 * XMLHttpRequest/window/document) — więc WebView.evaluateJavascript działa jako drop-in
 * zamiennik bez potrzeby pisania własnego parsera base.js.
 */
class YtEjsSolver(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val initMutex = Mutex()
    private var webView: WebView? = null

    private suspend fun ensureInitialized(): WebView {
        webView?.let { return it }
        return initMutex.withLock {
            webView ?: withContext(Dispatchers.Main) {
                val view = WebView(context).apply { settings.javaScriptEnabled = true }
                view.loadUrl("about:blank")
                val lib = context.assets.open("ejs/yt.solver.lib.min.js").bufferedReader().use { it.readText() }
                val core = context.assets.open("ejs/yt.solver.core.min.js").bufferedReader().use { it.readText() }
                // ładowanie 1:1 jak EJSBuilder w youtube_explode_dart: lib.min.js definiuje
                // lokalny `lib` (parsery meriyah/astring), Object.assign wystawia go na globalThis,
                // dopiero potem core.min.js może się do niego odwołać i zdefiniować globalne `jsc`
                evaluate(view, "$lib\nObject.assign(globalThis, lib);\n$core")
                webView = view
                view
            }
        }
    }

    /** Zwraca mapę wyzwanie → rozwiązanie dla podanych wyzwań "n" i/lub "sig". */
    suspend fun solve(playerJs: String, nChallenges: List<String>, sigChallenges: List<String>): Map<String, String> {
        if (nChallenges.isEmpty() && sigChallenges.isEmpty()) return emptyMap()
        val view = ensureInitialized()
        val requests = buildList {
            if (nChallenges.isNotEmpty()) add(JsonObject(mapOf("type" to JsonPrimitive("n"), "challenges" to JsonArray(nChallenges.map(::JsonPrimitive)))))
            if (sigChallenges.isNotEmpty()) add(JsonObject(mapOf("type" to JsonPrimitive("sig"), "challenges" to JsonArray(sigChallenges.map(::JsonPrimitive)))))
        }
        val input = JsonObject(mapOf(
            "type" to JsonPrimitive("player"),
            "player" to JsonPrimitive(playerJs),
            "requests" to JsonArray(requests),
        )).toString()
        val raw = withContext(Dispatchers.Main) { evaluate(view, "JSON.stringify(jsc($input))") }
        // evaluateJavascript zwraca wynik jako JSON-zakodowany literał JS-a — nasz skrypt sam robi
        // JSON.stringify, więc trzeba odkodować dwa razy: raz jako string z callbacku (to, co realnie
        // zwrócił evaluateJavascript), drugi raz jako JSON, który ten string faktycznie zawiera.
        val jsonText = json.parseToJsonElement(raw).jsonPrimitive.content
        val result = json.parseToJsonElement(jsonText).jsonObject
        val solutions = mutableMapOf<String, String>()
        (result["responses"] as? JsonArray)?.forEach { response ->
            val obj = response.jsonObject
            if (obj["type"]?.jsonPrimitive?.contentOrNull == "result") {
                (obj["data"] as? JsonObject)?.forEach { (challenge, solution) -> solutions[challenge] = solution.jsonPrimitive.content }
            }
        }
        return solutions
    }

    private suspend fun evaluate(view: WebView, script: String): String = suspendCancellableCoroutine { cont ->
        view.evaluateJavascript(script) { result -> cont.resume(result ?: "null") }
    }
}
