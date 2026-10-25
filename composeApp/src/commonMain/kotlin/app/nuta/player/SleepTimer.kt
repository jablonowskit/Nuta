package app.nuta.player

import app.nuta.domain.AudioPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Stan wyłącznika czasowego: wyłączony, odliczanie albo „do końca utworu”. */
sealed interface SleepTimerState {
    data object Off : SleepTimerState
    data class Countdown(val remainingMs: Long) : SleepTimerState
    data object EndOfTrack : SleepTimerState
}

/**
 * Wyłącznik czasowy: po czasie (albo gdy skończy się bieżący utwór) pauzuje odtwarzanie.
 * Żyje w [app.nuta.AppContainer], nie w kompozycji — odlicza dalej, gdy ekran jest wygaszony.
 */
class SleepTimer(
    private val player: AudioPlayer,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val state: StateFlow<SleepTimerState> = _state
    private var job: Job? = null

    fun startMinutes(minutes: Int) = restart {
        var remaining = minutes * 60_000L
        while (remaining > 0) {
            _state.value = SleepTimerState.Countdown(remaining)
            val step = minOf(TickMs, remaining)
            delay(step)
            remaining -= step
        }
        player.pause()
    }

    fun startEndOfTrack() = restart {
        _state.value = SleepTimerState.EndOfTrack
        val startIndex = player.state.value.currentIndex
        val startId = player.state.value.currentTrack?.id
        // Zmiana utworu = poprzedni się skończył (albo ktoś przełączył) — wtedy pauza.
        player.state.first { it.currentIndex != startIndex || it.currentTrack?.id != startId }
        player.pause()
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = SleepTimerState.Off
    }

    private fun restart(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch {
            try {
                block()
            } finally {
                if (job == coroutineContext[Job]) _state.value = SleepTimerState.Off
            }
        }
    }

    private companion object {
        const val TickMs = 1_000L
    }
}
