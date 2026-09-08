package app.nuta.listenbrainz

import app.nuta.core.logging.NutaLogger
import app.nuta.core.models.PlayerStatus
import app.nuta.core.models.Track
import app.nuta.domain.AudioPlayer
import app.nuta.net.httpPost
import app.nuta.settings.DataSource
import app.nuta.settings.PlaybackSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Zgłasza odsłuchania do ListenBrainz (`submit-listens`), obserwując stan [AudioPlayer].
 *
 * Po co: ListenBrainz generuje rekomendacje (`cf/recommendation`) oraz playlisty Daily/Weekly
 * Jams **wyłącznie** z historii odsłuchań użytkownika. Bez zgłaszania czegokolwiek te funkcje
 * nigdy nie dostroją się do tego, czego użytkownik naprawdę słucha w Nucie — więc scrobblowanie
 * domyka pętlę całego źródła danych ListenBrainz.
 *
 * Świadome decyzje:
 * - Działa **tylko** gdy `dataSource == LISTENBRAINZ`. W trybie Spotify nie wysyłamy niczego —
 *   ta sama zasada „jakby drugi serwis nie istniał", która rządzi całym [DataSource].
 * - Siedzi w `commonMain` i patrzy wyłącznie na `AudioPlayer.state`, więc jedna implementacja
 *   obsługuje Androida (Media3) i desktop (mpv) bez dotykania obu playerów.
 * - Źródłem audio (YouTube/SoundCloud) się nie przejmuje — zgłaszamy metadane utworu, a nie
 *   to, skąd akurat leciał strumień; `music_service` jest tylko informacyjne.
 * - Błędy sieci są cicho logowane i **nigdy** nie przerywają odtwarzania: nieudany scrobbel to
 *   utracony wpis w statystykach, a nie powód, żeby muzyka przestała grać.
 *
 * Endpointy zweryfikowane curlem 2026-09-08 na koncie testowym: `playing_now` (bez
 * `listened_at`, nie trafia do historii) i `single` (z `listened_at`, trwały wpis) — oba
 * zwróciły `{"status":"ok"}`, a wpis `single` odczytany z powrotem z `/user/{u}/listens`.
 */
class ListenBrainzScrobbler(
    private val settingsStore: PlaybackSettingsStore,
    private val logger: NutaLogger,
    /** Wstrzykiwalny zegar — pozwala testom sprawdzić wyliczanie `listened_at` bez czekania. */
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** Utwór, dla którego już zgłosiliśmy trwałe odsłuchanie — chroni przed duplikatami przy
        każdym tyknięciu pozycji oraz przy pauzie/wznowieniu tego samego utworu. */
    private var scrobbledKey: String? = null

    /** Utwór, dla którego zgłosiliśmy już „słucham teraz" — żeby nie powtarzać tego co sekundę. */
    private var nowPlayingKey: String? = null

    /**
     * Podłącza scrobblera do [player] w podanym [scope]. Zwraca natychmiast; obserwacja żyje
     * tak długo jak [scope] (na Androidzie skope'owane do procesu usługi/aplikacji).
     */
    fun attach(player: AudioPlayer, scope: CoroutineScope) {
        scope.launch {
            player.state
                .distinctUntilChanged { old, new ->
                    // Reagujemy tylko na zmianę utworu, statusu i pozycji zaokrąglonej do
                    // sekundy — bez tego każde tyknięcie pozycji budziłoby całą logikę.
                    old.currentTrack?.id == new.currentTrack?.id &&
                        old.status == new.status &&
                        old.positionMs / 1000 == new.positionMs / 1000
                }
                .collect { state ->
                    val track = state.currentTrack
                    if (track == null || state.status == PlayerStatus.IDLE) {
                        nowPlayingKey = null
                        return@collect
                    }
                    if (settingsStore.settings.value.dataSource != DataSource.LISTENBRAINZ) return@collect

                    val key = trackKey(track)
                    // Ten sam utwór puszczony ponownie (zapętlenie, powrót do niego później) to
                    // nowe odsłuchanie i należy je zgłosić jeszcze raz. Rozpoznajemy to po
                    // cofnięciu pozycji na początek — bez tego `scrobbledKey` blokowałby utwór
                    // na zawsze i drugie odsłuchanie przepadłoby.
                    if (key == scrobbledKey && state.positionMs < RestartPositionMs) {
                        scrobbledKey = null
                        nowPlayingKey = null
                    }
                    if (key != scrobbledKey && key != nowPlayingKey && state.status == PlayerStatus.PLAYING) {
                        nowPlayingKey = key
                        submitPlayingNow(track)
                    }
                    if (key != scrobbledKey && ListenThreshold.isReached(state.positionMs, state.durationMs)) {
                        scrobbledKey = key
                        submitListen(track, state.positionMs, state.durationMs)
                    }
                }
        }
    }

    /** Utwory z różnych źródeł mogą mieć ten sam (albo puste) `id`, więc kluczujemy też metadanymi. */
    private fun trackKey(track: Track): String = "${track.id}|${track.title}|${track.artists.firstOrNull().orEmpty()}"

    private suspend fun submitPlayingNow(track: Track) {
        post(
            event = "playing_now",
            body = requestBody(listenType = "playing_now", track = track, listenedAt = null, durationMs = track.durationMs),
            track = track,
        )
    }

    private suspend fun submitListen(track: Track, positionMs: Long, durationMs: Long) {
        // `listened_at` to moment ROZPOCZĘCIA odsłuchania, nie zgłoszenia — odejmujemy to, co
        // faktycznie zdążyło polecieć, żeby historia nie przesuwała się o próg (do 4 minut).
        val startedAtSeconds = (nowMs() - positionMs) / 1000
        post(
            event = "listen",
            body = requestBody(listenType = "single", track = track, listenedAt = startedAtSeconds, durationMs = durationMs),
            track = track,
        )
    }

    private suspend fun post(event: String, body: String, track: Track) {
        val settings = settingsStore.settings.value
        val token = settings.listenBrainzApiToken
        if (token.isBlank()) return
        runCatching {
            httpPost(
                SubmitListensUrl,
                headers = mapOf("Authorization" to "Token $token", "Content-Type" to "application/json"),
                body = body,
            )
        }.onSuccess {
            logger.info(
                "ListenBrainz", "scrobble_$event", "Zgłoszono odsłuchanie do ListenBrainz",
                fields = mapOf("title" to track.title, "artist" to track.artists.firstOrNull().orEmpty()),
            )
        }.onFailure { error ->
            // Celowo tylko ostrzeżenie: utracony scrobbel nie może zatrzymać odtwarzania.
            logger.warn(
                "ListenBrainz", "scrobble_failed", "Nie udało się zgłosić odsłuchania do ListenBrainz",
                fields = mapOf("event" to event, "title" to track.title, "reason" to (error.message ?: "unknown")),
            )
        }
    }

    private fun requestBody(listenType: String, track: Track, listenedAt: Long?, durationMs: Long): String {
        val additionalInfo = buildMap<String, JsonElement> {
            put("media_player", JsonPrimitive("Nuta"))
            put("submission_client", JsonPrimitive("Nuta"))
            // Utwory ze źródła ListenBrainz mają MBID jako `id`; ze Spotify — nie, i wtedy
            // pola nie wysyłamy wcale (serwis dopasuje utwór po tytule/wykonawcy przez
            // MessyBrainz, tak jak dla dowolnego innego klienta bez MBID).
            if (MbidRegex.matches(track.id)) put("recording_mbid", JsonPrimitive(track.id))
            if (durationMs > 0L) put("duration_ms", JsonPrimitive(durationMs))
        }
        val trackMetadata = buildMap<String, JsonElement> {
            put("artist_name", JsonPrimitive(track.artists.firstOrNull().orEmpty()))
            put("track_name", JsonPrimitive(track.title))
            if (track.album.isNotBlank()) put("release_name", JsonPrimitive(track.album))
            put("additional_info", JsonObject(additionalInfo))
        }
        val payloadEntry = buildMap<String, JsonElement> {
            if (listenedAt != null) put("listened_at", JsonPrimitive(listenedAt))
            put("track_metadata", JsonObject(trackMetadata))
        }
        return JsonObject(mapOf(
            "listen_type" to JsonPrimitive(listenType),
            "payload" to JsonArray(listOf(JsonObject(payloadEntry))),
        )).toString()
    }

    private companion object {
        const val SubmitListensUrl = "https://api.listenbrainz.org/1/submit-listens"
        /** Poniżej 3 s od początku uznajemy, że utwór wystartował od nowa (a nie że użytkownik
            przewinął w tył pod koniec) — margines na niedokładność raportowanej pozycji. */
        const val RestartPositionMs = 3_000L
        val MbidRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
