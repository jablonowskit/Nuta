package app.nuta.settings

import app.nuta.core.logging.NutaLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission

/**
 * `PlaybackSettingsStore` trwały na dysku — zgłoszone 19.09.2026: użytkownik testujący na
 * desktopie musiał wpisywać nazwę użytkownika i token ListenBrainz od nowa po każdym
 * uruchomieniu, bo [InMemoryPlaybackSettingsStore] (dotychczasowy `Main.kt`) żyje wyłącznie
 * w pamięci procesu.
 *
 * Zapis jest debounce'owany ([SaveDebounceMs]): `update()` woła się z UI synchronicznie przy
 * każdym wciśnięciu klawisza (np. wpisywanie username znak po znaku), a zapis do dysku na
 * każdy taki znak byłby marnotrawnym IO i potencjalnym zacinaniem. `StateFlow` aktualizuje się
 * natychmiast — UI nie czeka na dysk — tylko fizyczny zapis czeka na chwilę ciszy.
 *
 * Ścieżka: `$NUTA_SESSION_DIR/../playback-settings.json` gdy zmienna jest ustawiona (Docker,
 * patrz Dockerfile), inaczej `~/.local/share/nuta/playback-settings.json` — NIE sztywne
 * `/home/nuta/...` jak w [app.nuta.spotify.SpotifyCookieSessionStore]/`SpotifyTestTokenStore`,
 * bo te zakładają kontener Linux, a to store używane też przy uruchomieniu bezpośrednio na
 * hoście (Windows/macOS) przez `:composeApp:run`, gdzie `NUTA_SESSION_DIR` nigdy nie jest
 * ustawiona i katalog kontenera nie istnieje.
 *
 * Plik zawiera token ListenBrainz w czystym tekście (ten sam kompromis co
 * `SpotifyTestTokenStore.token.test.json` — jawnie testowe/deweloperskie rozwiązanie, nie
 * produkcyjne), więc dostaje te same restrykcyjne uprawnienia POSIX (tylko właściciel).
 * `setPosixFilePermissions` rzuca na Windows (brak POSIX) — stąd `runCatching`, tak samo jak
 * w `SpotifyTestTokenStore.save`.
 */
class FilePlaybackSettingsStore(
    private val scope: CoroutineScope,
    private val logger: NutaLogger,
) : PlaybackSettingsStore {
    private val file: Path = run {
        val sessionDir = System.getenv("NUTA_SESSION_DIR")
        val dir = if (!sessionDir.isNullOrBlank()) {
            Path.of(sessionDir).parent ?: Path.of(sessionDir)
        } else {
            Path.of(System.getProperty("user.home"), ".local", "share", "nuta")
        }
        dir.resolve("playback-settings.json")
    }

    private val state = MutableStateFlow(load())
    override val settings: StateFlow<YouTubePlaybackSettings> = state.asStateFlow()

    private var pendingSave: Job? = null

    override fun update(value: YouTubePlaybackSettings) {
        state.value = value
        // Nowa zmiana anuluje poprzednie oczekujące zapisanie — tylko ostatni stan po serii
        // szybkich zmian (np. wpisywanie tokenu znak po znaku) trafia na dysk, nie każdy z nich.
        pendingSave?.cancel()
        pendingSave = scope.launch {
            delay(SaveDebounceMs)
            save(value)
        }
    }

    private fun load(): YouTubePlaybackSettings = runCatching {
        if (!Files.exists(file)) return@runCatching YouTubePlaybackSettings()
        val root = Json.parseToJsonElement(Files.readString(file)).jsonObject
        val defaults = YouTubePlaybackSettings()
        YouTubePlaybackSettings(
            fontScale = root["fontScale"]?.jsonPrimitive?.float ?: defaults.fontScale,
            quality = root["quality"]?.jsonPrimitive?.contentOrNull?.let(::enumOrNull) ?: defaults.quality,
            codec = root["codec"]?.jsonPrimitive?.contentOrNull?.let(::enumOrNull) ?: defaults.codec,
            bufferSize = root["bufferSize"]?.jsonPrimitive?.contentOrNull?.let(::enumOrNull) ?: defaults.bufferSize,
            loudnessNormalization = root["loudnessNormalization"]?.jsonPrimitive?.contentOrNull?.let(::enumOrNull) ?: defaults.loudnessNormalization,
            youtubeClientProfile = root["youtubeClientProfile"]?.jsonPrimitive?.contentOrNull?.let(::enumOrNull) ?: defaults.youtubeClientProfile,
            audioSource = root["audioSource"]?.jsonPrimitive?.contentOrNull?.let(::enumOrNull) ?: defaults.audioSource,
            dataSource = root["dataSource"]?.jsonPrimitive?.contentOrNull?.let(::enumOrNull) ?: defaults.dataSource,
            listenBrainzUsername = root["listenBrainzUsername"]?.jsonPrimitive?.contentOrNull ?: defaults.listenBrainzUsername,
            listenBrainzApiToken = root["listenBrainzApiToken"]?.jsonPrimitive?.contentOrNull ?: defaults.listenBrainzApiToken,
            prefetchEnabled = root["prefetchEnabled"]?.jsonPrimitive?.boolean ?: defaults.prefetchEnabled,
            playerCollapsed = root["playerCollapsed"]?.jsonPrimitive?.boolean ?: defaults.playerCollapsed,
            cacheSizeMb = root["cacheSizeMb"]?.jsonPrimitive?.int ?: defaults.cacheSizeMb,
        ).also {
            logger.info("PlaybackSettings", "settings_loaded", "Odtworzono ustawienia odtwarzania z dysku", fields = mapOf("path" to file.toString()))
        }
    }.getOrElse { error ->
        // Uszkodzony/nieczytelny plik nie może zablokować startu aplikacji — wracamy do
        // domyślnych ustawień, tak jak przy pierwszym uruchomieniu.
        logger.warn("PlaybackSettings", "settings_load_failed", "Nie udało się odczytać ustawień z dysku — używam domyślnych", fields = mapOf("reason" to (error.message ?: "unknown")))
        YouTubePlaybackSettings()
    }

    private fun save(value: YouTubePlaybackSettings) {
        runCatching {
            Files.createDirectories(file.parent)
            val payload = buildJsonObject {
                put("fontScale", value.fontScale)
                put("quality", value.quality.name)
                put("codec", value.codec.name)
                put("bufferSize", value.bufferSize.name)
                put("loudnessNormalization", value.loudnessNormalization.name)
                put("youtubeClientProfile", value.youtubeClientProfile.name)
                put("audioSource", value.audioSource.name)
                put("dataSource", value.dataSource.name)
                put("listenBrainzUsername", value.listenBrainzUsername)
                put("listenBrainzApiToken", value.listenBrainzApiToken)
                put("prefetchEnabled", value.prefetchEnabled)
                put("playerCollapsed", value.playerCollapsed)
                put("cacheSizeMb", value.cacheSizeMb)
            }.toString()
            Files.writeString(file, payload, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            runCatching {
                Files.setPosixFilePermissions(file, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            }
        }.onFailure { error ->
            // Nieudany zapis nie może przerwać działania apki — ustawienie i tak już działa
            // w StateFlow, tylko nie przetrwa do następnego uruchomienia.
            logger.warn("PlaybackSettings", "settings_save_failed", "Nie udało się zapisać ustawień na dysk", fields = mapOf("reason" to (error.message ?: "unknown")))
        }
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        runCatching { enumValueOf<T>(name) }.getOrNull()

    private companion object {
        /** Zapis dopiero po chwili ciszy — bez tego wpisywanie tokenu znak po znaku odpalałoby
            zapis pliku przy każdym naciśnięciu klawisza. */
        const val SaveDebounceMs = 500L
    }
}
