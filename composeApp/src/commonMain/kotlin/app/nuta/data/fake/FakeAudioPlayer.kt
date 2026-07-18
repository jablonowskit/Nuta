package app.nuta.data.fake

import app.nuta.core.logging.NutaLogger
import app.nuta.core.models.PlayerState
import app.nuta.core.models.PlayerStatus
import app.nuta.core.models.Track
import app.nuta.domain.AudioPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FakeAudioPlayer(
    private val scope: CoroutineScope,
    private val logger: NutaLogger,
) : AudioPlayer {
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()
    private var ticker: Job? = null

    override suspend fun setQueue(tracks: List<Track>, startIndex: Int) {
        val safeIndex = if (tracks.isEmpty()) -1 else startIndex.coerceIn(tracks.indices)
        _state.value = PlayerState(status = if (tracks.isEmpty()) PlayerStatus.IDLE else PlayerStatus.PAUSED, queue = tracks, currentIndex = safeIndex)
        logger.info("FakeAudioPlayer", "queue_set", "Ustawiono kolejkę demonstracyjną", fields = mapOf("size" to tracks.size.toString(), "index" to safeIndex.toString()))
    }

    override suspend fun appendToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        _state.value = _state.value.copy(queue = _state.value.queue + tracks)
        logger.info("FakeAudioPlayer", "queue_appended", "Dopisano utwory do kolejki", fields = mapOf("count" to tracks.size.toString()))
    }

    override suspend fun shuffleUpcoming() {
        val state = _state.value
        if (state.currentIndex !in state.queue.indices) return
        _state.value = state.copy(queue = state.queue.take(state.currentIndex + 1) + state.queue.drop(state.currentIndex + 1).shuffled(), shuffleEnabled = true)
        logger.info("FakeAudioPlayer", "queue_shuffled", "Przetasowano pozostałe utwory kolejki")
    }

    override suspend fun play() {
        if (_state.value.currentTrack == null) return
        _state.value = _state.value.copy(status = PlayerStatus.PLAYING, errorMessage = null)
        logger.info("FakeAudioPlayer", "play", "Rozpoczęto symulowane odtwarzanie", fields = currentFields())
        startTicker()
    }

    override suspend fun pause() {
        if (_state.value.currentTrack == null) return
        _state.value = _state.value.copy(status = PlayerStatus.PAUSED)
        ticker?.cancel()
        logger.info("FakeAudioPlayer", "pause", "Wstrzymano symulowane odtwarzanie", fields = currentFields())
    }

    override suspend fun stop() {
        ticker?.cancel()
        _state.value = _state.value.copy(status = PlayerStatus.IDLE, positionMs = 0, errorMessage = null)
        logger.info("FakeAudioPlayer", "stop", "Zatrzymano symulowane odtwarzanie", fields = currentFields())
    }

    override suspend fun seekTo(positionMs: Long) {
        val duration = _state.value.durationMs
        _state.value = _state.value.copy(positionMs = positionMs.coerceIn(0L, duration))
        logger.debug("FakeAudioPlayer", "seek", "Zmieniono pozycję", fields = currentFields() + ("positionMs" to _state.value.positionMs.toString()))
    }

    override suspend fun next() = moveTo(_state.value.currentIndex + 1, "next")
    override suspend fun previous() = moveTo(_state.value.currentIndex - 1, "previous")
    override suspend fun playAt(index: Int) = moveTo(index, "queue_item_selected")

    override suspend fun simulateError() {
        ticker?.cancel()
        _state.value = _state.value.copy(status = PlayerStatus.ERROR, errorMessage = "Symulowany błąd playera")
        logger.error("FakeAudioPlayer", "simulated_error", "Wymuszono błąd demonstracyjny", fields = currentFields())
    }

    private suspend fun moveTo(index: Int, event: String) {
        val queue = _state.value.queue
        if (queue.isEmpty()) return
        val target = index.coerceIn(queue.indices)
        val wasPlaying = _state.value.status == PlayerStatus.PLAYING
        _state.value = _state.value.copy(currentIndex = target, positionMs = 0, status = if (wasPlaying) PlayerStatus.PLAYING else PlayerStatus.PAUSED, errorMessage = null)
        logger.info("FakeAudioPlayer", event, "Zmieniono aktualny utwór", fields = currentFields())
        if (wasPlaying) startTicker()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && _state.value.status == PlayerStatus.PLAYING) {
                delay(1_000)
                val nextPosition = _state.value.positionMs + 1_000
                if (nextPosition >= _state.value.durationMs) {
                    if (_state.value.currentIndex < _state.value.queue.lastIndex) {
                        moveTo(_state.value.currentIndex + 1, "auto_next")
                    } else {
                        _state.value = _state.value.copy(positionMs = _state.value.durationMs, status = PlayerStatus.ENDED)
                        logger.info("FakeAudioPlayer", "queue_ended", "Zakończono kolejkę demonstracyjną")
                        break
                    }
                } else {
                    _state.value = _state.value.copy(positionMs = nextPosition)
                }
            }
        }
    }

    private fun currentFields(): Map<String, String> = mapOf(
        "trackId" to (_state.value.currentTrack?.id ?: "none"),
        "index" to _state.value.currentIndex.toString(),
    )
}
