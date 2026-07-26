package app.nuta.spotify

import app.nuta.core.logging.NutaLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Uruchamia natywny helper logowania (native/spotify-login, WebView2 na Windows)
 * jako osobny proces, czyta jego stdout (jedna linia JSON) i zwraca cookie sp_dc.
 * Helper istnieje wyłącznie po to, by ominąć błąd HTTP 400 na reCAPTCHA, na który
 * trafia JCEF — patrz docs/WEBVIEW2_MIGRATION_PLAN.md.
 */
class SpotifyLoginHelperClient(private val logger: NutaLogger) {
    private val json = Json { ignoreUnknownKeys = true }

    /** @return cookie sp_dc odczytane przez helper, albo null jeśli użytkownik zamknął okno bez logowania. */
    suspend fun runLogin(): String? = withContext(Dispatchers.IO) {
        val exe = locateHelper()
        logger.info("SpotifyLoginHelper", "helper_starting", "Uruchamianie helpera logowania WebView2")

        val process = ProcessBuilder(exe.absolutePath).start()
        // Wyjście helpera to jedna krótka linia JSON tuż przed zakończeniem procesu —
        // dla tak małego wolumenu czytanie strumieni dopiero po waitFor() jest bezpieczne
        // (klasyczny deadlock ProcessBuilder groziłby dopiero przy dużych/ciągłych danych).
        val finished = process.waitFor(LOGIN_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        if (!finished) {
            process.destroyForcibly()
            error("Przekroczono czas oczekiwania na logowanie Spotify")
        }

        when (val exitCode = process.exitValue()) {
            0 -> {
                val output = process.inputStream.bufferedReader().readText().trim()
                val lastLine = output.lineSequence().lastOrNull { it.isNotBlank() }
                    ?: error("Helper zwrócił kod 0, ale nic nie wypisał na stdout")
                val root = json.parseToJsonElement(lastLine).jsonObject
                val spDc = root["spDc"]?.jsonPrimitive?.contentOrNull
                require(!spDc.isNullOrBlank()) { "Helper zwrócił kod 0, ale brak sp_dc w wyniku" }
                logger.info("SpotifyLoginHelper", "helper_succeeded", "Helper zwrócił sesję Spotify")
                spDc
            }
            1 -> {
                logger.info("SpotifyLoginHelper", "helper_cancelled", "Użytkownik zamknął okno logowania bez zalogowania")
                null
            }
            else -> {
                val errorOutput = process.errorStream.bufferedReader().readText()
                logger.error(
                    "SpotifyLoginHelper", "helper_failed", "Helper logowania zakończył się błędem",
                    fields = mapOf("exitCode" to exitCode.toString(), "stderr" to errorOutput.take(500)),
                )
                error("Helper logowania zakończył się kodem $exitCode")
            }
        }
    }

    private fun locateHelper(): File {
        val resourcesDir = System.getProperty("compose.application.resources.dir")
            ?: error("Brak compose.application.resources.dir — helper logowania jest dostępny tylko w spakowanej wersji Windows")
        // Compose Desktop spłaszcza appResourcesRootDir przy pakowaniu: zawartość
        // resources/windows/ trafia bezpośrednio do app/resources (bez prefiksu "windows").
        val exe = File(resourcesDir, "SpotifyLoginHelper.exe")
        check(exe.isFile) { "Nie znaleziono helpera logowania: ${exe.absolutePath}" }
        return exe
    }

    companion object {
        private const val LOGIN_TIMEOUT_MINUTES = 5L
    }
}
