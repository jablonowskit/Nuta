package app.nuta.update

import app.nuta.core.BuildInfo
import app.nuta.net.httpGet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val version: String) : UpdateState
    data class Downloading(val version: String, val progress: Float) : UpdateState
    data class Failed(val reason: String) : UpdateState
}

/**
 * Aktualizacja aplikacji bez adb: CI po każdym buildzie `main` podmienia pliki w release
 * „nightly” na GitHubie (`nuta.apk` + `nuta-version.txt` z krótkim SHA). Artefakty z Actions
 * nie nadają się, bo pobranie wymaga zalogowania.
 *
 * SHA nie da się porównać „starszy/nowszy” — release to zawsze ostatni build `main`, więc każde
 * inne SHA niż zainstalowane traktujemy jako aktualizację. Build lokalny („dev”) nie porównuje.
 * Instalację robi platforma ([install]) — jest tylko na Androidzie.
 */
class AppUpdater(
    private val install: suspend (apkUrl: String, onProgress: (Float) -> Unit) -> Unit,
    private val fetchVersion: suspend () -> String = { httpGet(VersionUrl) },
    private val installedVersion: String = BuildInfo.GIT_SHA,
) {
    private val stateFlow = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = stateFlow.asStateFlow()

    suspend fun check() {
        if (stateFlow.value is UpdateState.Downloading || stateFlow.value is UpdateState.Checking) return
        stateFlow.value = UpdateState.Checking
        stateFlow.value = try {
            newerVersion(fetchVersion(), installedVersion)?.let(UpdateState::Available) ?: UpdateState.UpToDate
        } catch (e: CancellationException) {
            stateFlow.value = UpdateState.Idle
            throw e
        } catch (e: Exception) {
            UpdateState.Failed(e.message ?: "?")
        }
    }

    /** Pobiera APK i oddaje je systemowi; potwierdzenie instalacji pokazuje już Android. */
    suspend fun downloadAndInstall() {
        val available = stateFlow.value as? UpdateState.Available ?: return
        stateFlow.value = UpdateState.Downloading(available.version, 0f)
        stateFlow.value = try {
            install(ApkUrl) { progress -> stateFlow.value = UpdateState.Downloading(available.version, progress) }
            // zostaje „dostępna”: jeśli ktoś anuluje systemowe okno, może spróbować jeszcze raz
            available
        } catch (e: CancellationException) {
            stateFlow.value = available
            throw e
        } catch (e: Exception) {
            UpdateState.Failed(e.message ?: "?")
        }
    }

    companion object {
        private const val ReleaseUrl = "https://github.com/jablonowskit/Nuta/releases/download/nightly"
        const val VersionUrl = "$ReleaseUrl/nuta-version.txt"
        const val ApkUrl = "$ReleaseUrl/nuta.apk"
        private val ShortSha = Regex("^[0-9a-f]{7}$")

        internal fun newerVersion(remote: String, installed: String): String? {
            val version = remote.trim()
            return version.takeIf { ShortSha.matches(it) && ShortSha.matches(installed) && it != installed }
        }
    }
}
