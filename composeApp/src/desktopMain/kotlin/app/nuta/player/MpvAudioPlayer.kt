package app.nuta.player

import app.nuta.core.logging.NutaLogger
import app.nuta.core.models.PlayerState
import app.nuta.core.models.PlayerStatus
import app.nuta.core.models.Track
import app.nuta.domain.AudioPlayer
import app.nuta.settings.LoudnessNormalization
import app.nuta.settings.PlaybackSettingsStore
import app.nuta.youtube.YouTubeMediaService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class MpvAudioPlayer(
    private val scope: CoroutineScope,
    private val youtube: YouTubeMediaService,
    private val logger: NutaLogger,
    private val settingsStore: PlaybackSettingsStore,
) : AudioPlayer {
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val loadMutex = Mutex()
    private val socketPath = Path.of("/tmp", "nuta-mpv-${UUID.randomUUID()}.sock")
    private var process: Process? = null
    private var ticker: Job? = null
    private var processLogReader: Job? = null
    private var commandId = 0L
    // Rośnie przy każdej zmianie utworu/zatrzymaniu. Automatyczne przejście na koniec
    // utworu porównuje swój znacznik po zdobyciu loadMutex, żeby nie nadpisać wyboru
    // użytkownika, który w tym samym momencie kliknął next/stop.
    private var playbackGeneration = 0L

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion { shutdown() }
        scope.launch {
            settingsStore.settings.collect { settings ->
                if (process?.isAlive == true) runCatching { sendCommand("set_property", "af", loudnormFilter(settings.loudnessNormalization)) }
            }
        }
    }

    override suspend fun setQueue(tracks: List<Track>, startIndex: Int) {
        require(startIndex in tracks.indices || tracks.isEmpty()) { "Nieprawidłowy indeks kolejki" }
        loadMutex.withLock {
            stopPlaybackLocked()
            _state.value = PlayerState(queue = tracks, currentIndex = if (tracks.isEmpty()) -1 else startIndex)
        }
        logger.info("MpvPlayer", "queue_set", "Ustawiono kolejkę playera", fields = mapOf("count" to tracks.size.toString()))
    }

    override suspend fun appendToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        _state.value = _state.value.copy(queue = _state.value.queue + tracks)
        logger.info("MpvPlayer", "queue_appended", "Dopisano utwory do kolejki", fields = mapOf("count" to tracks.size.toString(), "queueSize" to _state.value.queue.size.toString()))
    }

    override suspend fun shuffleUpcoming() {
        val state = _state.value
        if (state.currentIndex !in state.queue.indices) return
        _state.value = state.copy(queue = state.queue.take(state.currentIndex + 1) + state.queue.drop(state.currentIndex + 1).shuffled(), shuffleEnabled = true)
        logger.info("MpvAudioPlayer", "queue_shuffled", "Przetasowano pozostałe utwory kolejki")
    }

    override suspend fun removeFromQueue(index: Int): Unit = loadMutex.withLock {
        val state = _state.value
        if (index !in state.queue.indices) return@withLock
        val newQueue = state.queue.toMutableList().apply { removeAt(index) }
        if (newQueue.isEmpty()) { clearQueueLocked(); return@withLock }
        when {
            index < state.currentIndex -> {
                _state.value = state.copy(queue = newQueue, currentIndex = state.currentIndex - 1)
            }
            index == state.currentIndex -> {
                val wasPlaying = state.status == PlayerStatus.PLAYING || state.status == PlayerStatus.LOADING
                stopPlaybackLocked()
                _state.value = _state.value.copy(queue = newQueue, currentIndex = index.coerceAtMost(newQueue.lastIndex), positionMs = 0, status = PlayerStatus.IDLE, errorMessage = null)
                if (wasPlaying) loadCurrentLocked()
            }
            else -> {
                _state.value = state.copy(queue = newQueue)
            }
        }
        logger.info("MpvPlayer", "queue_item_removed", "Usunięto utwór z kolejki")
    }

    override suspend fun clearQueue(): Unit = loadMutex.withLock { clearQueueLocked() }

    override suspend fun play() {
        if (_state.value.status == PlayerStatus.PAUSED) {
            loadMutex.withLock {
                // Ponowne sprawdzenie pod blokadą — inna operacja mogła w tym czasie
                // zmienić utwór albo zatrzymać odtwarzanie.
                if (_state.value.status != PlayerStatus.PAUSED) return@withLock
                sendCommand("set_property", "pause", false)
                _state.value = _state.value.copy(status = PlayerStatus.PLAYING)
                startTicker()
            }
            return
        }
        loadMutex.withLock { loadCurrentLocked() }
    }

    override suspend fun pause(): Unit = loadMutex.withLock {
        if (_state.value.status != PlayerStatus.PLAYING) return@withLock
        sendCommand("set_property", "pause", true)
        ticker?.cancel()
        _state.value = _state.value.copy(status = PlayerStatus.PAUSED)
    }

    override suspend fun stop(): Unit = loadMutex.withLock {
        stopPlaybackLocked()
        _state.value = _state.value.copy(status = PlayerStatus.IDLE, positionMs = 0, errorMessage = null)
        logger.info("MpvPlayer", "playback_stopped", "Zatrzymano odtwarzanie audio")
    }

    /** Wymaga trzymania [loadMutex]. */
    private suspend fun loadCurrentLocked() {
        val current = _state.value.currentTrack ?: return
        playbackGeneration++
        _state.value = _state.value.copy(status = PlayerStatus.LOADING, positionMs = 0, errorMessage = null)
        logger.info("MpvPlayer", "track_resolving", "Rozpoczęto przygotowanie źródła audio")
        try {
            val resolution = youtube.resolve(current)
            youtube.validate(resolution.stream)
            ensureProcess()
            val loadCommand = resolution.stream.url.use { url -> arrayOf("loadfile", url, "replace") }
            sendCommand(*loadCommand)
            sendCommand("set_property", "af", loudnormFilter(settingsStore.settings.value.loudnessNormalization))
            sendCommand("set_property", "pause", false)
            _state.value = _state.value.copy(status = PlayerStatus.PLAYING)
            logger.info("MpvPlayer", "playback_started", "Rozpoczęto odtwarzanie audio", fields = mapOf("codec" to resolution.stream.codec, "container" to resolution.stream.container))
            startTicker()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            // Nieudany start zostawiłby proces mpv w niespójnym stanie — sprzątamy,
            // żeby kolejne play() nie uznało półmartwego procesu za gotowy.
            shutdownLocked()
            _state.value = _state.value.copy(status = PlayerStatus.ERROR, errorMessage = error.message)
            logger.error("MpvPlayer", "playback_failed", "Nie udało się uruchomić odtwarzania", throwable = error)
        }
    }

    /** Wymaga trzymania [loadMutex]. */
    private suspend fun stopPlaybackLocked() {
        playbackGeneration++
        ticker?.cancel()
        if (process?.isAlive == true) runCatching { sendCommand("stop") }
    }

    /** Wymaga trzymania [loadMutex]. */
    private suspend fun clearQueueLocked() {
        stopPlaybackLocked()
        _state.value = PlayerState()
        logger.info("MpvPlayer", "queue_cleared", "Wyczyszczono kolejkę")
    }

    override suspend fun seekTo(positionMs: Long) {
        // durationMs bywa 0, gdy katalog źródła nie podał długości utworu (np. wynik
        // MusicBrainz z length=null, utwór z lb-radio bez pola duration) — przycinanie do
        // 0..0 zawsze wracałoby na początek, mimo że sam strumień faktycznie da się przewijać.
        val duration = _state.value.durationMs
        val bounded = if (duration > 0) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
        sendCommand("seek", bounded / 1_000.0, "absolute", "exact")
        _state.value = _state.value.copy(positionMs = bounded)
    }

    override suspend fun next() = moveTo(_state.value.currentIndex + 1)
    override suspend fun previous() = moveTo(_state.value.currentIndex - 1)
    override suspend fun playAt(index: Int) = moveTo(index)
    override suspend fun simulateError() { ticker?.cancel(); _state.value = _state.value.copy(status = PlayerStatus.ERROR, errorMessage = "Symulowany błąd playera") }

    private suspend fun moveTo(index: Int): Unit = loadMutex.withLock {
        if (index !in _state.value.queue.indices) return@withLock
        stopPlaybackLocked()
        _state.value = _state.value.copy(currentIndex = index, status = PlayerStatus.IDLE, positionMs = 0, errorMessage = null)
        loadCurrentLocked()
    }

    private suspend fun ensureProcess() {
        if (process?.isAlive == true && Files.exists(socketPath)) return
        shutdownLocked()
        val output = System.getenv("NUTA_MPV_AUDIO_OUTPUT")?.takeIf(String::isNotBlank) ?: "auto"
        logger.debug("MpvPlayer", "process_starting", "Uruchamianie procesu mpv", fields = mapOf("audioOutput" to output))
        process = ProcessBuilder(
            "mpv", "--idle=yes", "--no-video", "--audio-display=no", "--no-terminal",
            "--msg-level=all=warn", "--ao=$output", "--input-ipc-server=$socketPath",
        ).redirectErrorStream(true).start()
        processLogReader = scope.launch(Dispatchers.IO) {
            process?.inputStream?.bufferedReader()?.useLines { lines ->
                lines.forEach { line ->
                    logger.trace("MpvPlayer", "mpv_output", "Komunikat procesu mpv", fields = mapOf("output" to sanitizeMpvOutput(line)))
                }
            }
        }
        repeat(50) {
            if (Files.exists(socketPath)) {
                logger.info("MpvPlayer", "process_started", "Proces mpv jest gotowy", fields = mapOf("pid" to (process?.pid()?.toString() ?: "unknown")))
                return
            }
            if (process?.isAlive != true) error("Proces mpv zakończył się podczas uruchamiania")
            delay(100)
        }
        error("Nie powstał socket IPC mpv")
    }

    // Blokujący odczyt z socketu mpv potrafi nigdy nie wrócić, gdy proces zawiesi się
    // (np. po awarii urządzenia audio) — bez limitu czasu zablokowałby cały ticker.
    private suspend fun sendCommand(vararg values: Any): JsonObject = withTimeout(IPC_TIMEOUT_MS) {
        sendCommandInternal(*values)
    }

    private suspend fun sendCommandInternal(vararg values: Any): JsonObject = withContext(Dispatchers.IO) {
        val requestId = synchronized(this@MpvAudioPlayer) { ++commandId }
        val command = JsonArray(values.map { if (it is Boolean) JsonPrimitive(it) else if (it is Number) JsonPrimitive(it) else JsonPrimitive(it.toString()) })
        val commandName = values.firstOrNull()?.toString() ?: "unknown"
        logger.trace("MpvPlayer", "ipc_command_sent", "Wysyłanie komendy IPC", fields = mapOf("command" to commandName, "requestId" to requestId.toString()))
        val payload = "{\"command\":$command,\"request_id\":$requestId}\n".toByteArray(StandardCharsets.UTF_8)
        SocketChannel.open(StandardProtocolFamily.UNIX).use { socket ->
            socket.connect(UnixDomainSocketAddress.of(socketPath))
            val buffer = ByteBuffer.wrap(payload)
            while (buffer.hasRemaining()) socket.write(buffer)
            val responseBytes = ByteBuffer.allocate(64 * 1024)
            while (responseBytes.position() < responseBytes.capacity()) {
                val read = socket.read(responseBytes)
                if (read < 0 || (responseBytes.position() > 0 && responseBytes.get(responseBytes.position() - 1) == '\n'.code.toByte())) break
            }
            responseBytes.flip()
            val responseText = StandardCharsets.UTF_8.decode(responseBytes).toString().trim()
            // MPV may append asynchronous events (for example `idle`) to the
            // same socket read. Pick the object belonging to this command.
            val response = responseText.lineSequence()
                .filter(String::isNotBlank)
                .mapNotNull { line ->
                    runCatching { kotlinx.serialization.json.Json.parseToJsonElement(line).jsonObject }.getOrNull()
                }
                .firstOrNull { candidate ->
                    candidate["request_id"]?.jsonPrimitive?.content == requestId.toString()
                }
                ?: error("Brak odpowiedzi mpv dla komendy $commandName (requestId=$requestId)")
            val error = response["error"]?.jsonPrimitive?.content ?: "missing"
            logger.trace("MpvPlayer", "ipc_response_received", "Odebrano odpowiedź IPC", fields = mapOf("command" to commandName, "requestId" to requestId.toString(), "result" to error))
            // At EOF some properties disappear before idle-active becomes true.
            // Keep polling so the queue can advance on that reliable signal.
            if (error != "success" && !(commandName == "get_property" && error == "property unavailable")) {
                error("mpv odrzucił komendę $commandName: $error")
            }
            response
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        val generation = playbackGeneration
        ticker = scope.launch {
            while (isActive && _state.value.status == PlayerStatus.PLAYING) {
                delay(1_000)
                try {
                    if (process?.isAlive != true) error("Proces mpv nie działa")
                    val position = property("time-pos").asNumberOrNull()?.times(1_000)?.toLong()
                    val paused = property("pause").asBooleanOrNull()
                    val eof = property("eof-reached").asBooleanOrNull() == true
                    val idle = property("idle-active").asBooleanOrNull() == true
                    logger.trace(
                        "MpvPlayer", "playback_state_polled", "Odczytano stan odtwarzania z mpv",
                        fields = mapOf(
                            "positionMs" to (position?.toString() ?: "unavailable"),
                            "paused" to (paused?.toString() ?: "unavailable"),
                            "eof" to eof.toString(), "idle" to idle.toString(),
                        ),
                    )
                    // Strażnik generacji jak niżej przy utracie audio: odczyt `time-pos` idzie
                    // przez IPC, więc po powrocie utwór mógł już się zmienić (auto-przejście
                    // kolejki zeruje positionMs). Bez tego stara pozycja nadpisywała nową i
                    // scrobbler widział pozycję z POPRZEDNIEGO utworu — ten sam błąd, który na
                    // Androidzie dał błędny wpis w ListenBrainz (patrz Media3AudioPlayer.startTicker).
                    if (generation != playbackGeneration) return@launch
                    if (position != null) _state.value = _state.value.copy(positionMs = position.coerceAtLeast(0))
                    // Odpowiednik ACTION_AUDIO_BECOMING_NOISY z Androida: gdy zniknie
                    // urządzenie wyjściowe (Bluetooth/słuchawki), mpv zwalnia AO i dalej
                    // "odtwarza" w ciszę. Pauzujemy, zamiast gubić pozycję w utworze.
                    if (!eof && !idle && audioOutputLost()) {
                        logger.info("MpvPlayer", "audio_output_lost", "Utracono urządzenie audio — pauzuję odtwarzanie")
                        scope.launch {
                            loadMutex.withLock {
                                if (generation != playbackGeneration) return@withLock
                                if (_state.value.status != PlayerStatus.PLAYING) return@withLock
                                runCatching { sendCommand("set_property", "pause", true) }
                                _state.value = _state.value.copy(status = PlayerStatus.PAUSED)
                            }
                        }
                        break
                    }
                    if (eof || idle) {
                        scope.launch {
                            loadMutex.withLock {
                                // Użytkownik mógł w tym czasie przełączyć utwór lub zatrzymać
                                // odtwarzanie — wtedy to auto-przejście jest już nieaktualne.
                                if (generation != playbackGeneration) return@withLock
                                val nextIndex = _state.value.currentIndex + 1
                                if (nextIndex in _state.value.queue.indices) {
                                    _state.value = _state.value.copy(
                                        currentIndex = nextIndex,
                                        status = PlayerStatus.IDLE,
                                        positionMs = 0,
                                        errorMessage = null,
                                    )
                                    logger.info(
                                        "MpvPlayer",
                                        "queue_auto_advance",
                                        "Automatyczne przejście do następnego utworu",
                                        fields = mapOf(
                                            "nextIndex" to nextIndex.toString(),
                                            "queueSize" to _state.value.queue.size.toString(),
                                        ),
                                    )
                                    loadCurrentLocked()
                                } else {
                                    _state.value = _state.value.copy(status = PlayerStatus.ENDED)
                                    logger.info("MpvPlayer", "playback_ended", "MPV zakończył ostatni utwór kolejki")
                                }
                            }
                        }
                        break
                    }
                    if (paused == true) _state.value = _state.value.copy(status = PlayerStatus.PAUSED)
                } catch (error: Throwable) {
                    _state.value = _state.value.copy(status = PlayerStatus.ERROR, errorMessage = error.message)
                    logger.error("MpvPlayer", "playback_monitor_failed", "Utracono kontakt z procesem mpv", throwable = error)
                    break
                }
            }
        }
    }

    private suspend fun property(name: String): JsonElement = sendCommand("get_property", name)["data"] ?: JsonNull

    /**
     * Wykrywa zniknięcie wyjścia audio. Przy aktywnym odtwarzaniu mpv zawsze ma
     * zainicjowane AO — jego brak oznacza, że urządzenie zostało odłączone.
     */
    private suspend fun audioOutputLost(): Boolean {
        val response = try {
            sendCommand("get_property", "current-ao")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Nie potrafimy odczytać właściwości — nie zgadujemy utraty urządzenia,
            // bo błąd IPC obsługuje już nadrzędny catch tickera.
            return false
        }
        // Brak pola "data" to odpowiedź "property unavailable" (mpv nie zdążył jeszcze
        // zainicjować AO po loadfile) — to nie to samo co zniknięcie urządzenia.
        val data = response["data"] ?: return false
        return data is JsonNull || (data as? JsonPrimitive)?.content.isNullOrBlank()
    }

    private fun JsonElement.asNumberOrNull(): Double? = (this as? JsonPrimitive)?.doubleOrNull

    private fun JsonElement.asBooleanOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

    private fun sanitizeMpvOutput(line: String): String = line.replace(Regex("https?://\\S+"), "[STREAM_URL_REDACTED]").take(2_000)

    private companion object {
        const val IPC_TIMEOUT_MS = 5_000L
    }

    private fun loudnormFilter(mode: LoudnessNormalization): String = when (mode) {
        LoudnessNormalization.OFF -> ""
        LoudnessNormalization.GENTLE -> "lavfi=[loudnorm=I=-20:TP=-1.5:LRA=20]"
        LoudnessNormalization.NORMAL -> "lavfi=[loudnorm=I=-16:TP=-1.5:LRA=11]"
    }

    /**
     * Wywoływane z [invokeOnCompletion] na dowolnym wątku, gdy scope aplikacji już nie żyje —
     * nie da się tu zaczekać na [loadMutex], więc sprzątamy bez blokady. To bezpieczne,
     * bo po anulowaniu scope żadne nowe play() nie wystartuje.
     */
    private fun shutdown() = shutdownLocked()

    /** Wymaga trzymania [loadMutex] (albo martwego scope — patrz [shutdown]). */
    private fun shutdownLocked() {
        ticker?.cancel()
        processLogReader?.cancel()
        // Cancel korutyny nie przerywa blokującego readLine() — zamknięcie strumienia tak.
        runCatching { process?.inputStream?.close() }
        runCatching { process?.destroyForcibly() }
        process = null
        runCatching { Files.deleteIfExists(socketPath) }
    }
}
