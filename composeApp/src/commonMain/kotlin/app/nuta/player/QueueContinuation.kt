package app.nuta.player

import app.nuta.core.logging.NutaLogger
import app.nuta.core.models.PlayerStatus
import app.nuta.domain.AudioPlayer
import app.nuta.domain.SpotifyRepository
import app.nuta.settings.PlaybackSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Dokłada podobne utwory (radio od bieżącego) na końcu kolejki. Działa poza UI, tak jak
 * [SleepTimer]: efekty Compose nie dostają klatek przy wygaszonym ekranie, a właśnie wtedy
 * kolejka najczęściej dobiega końca.
 *
 * Dwa tryby:
 * - „Graj dalej” (ustawienie `autoContinue`): dokłada dopiero, gdy gra ostatni utwór.
 * - tryb podobnych (przycisk ♬+, [similarMode]): dokłada już przy ≤[SimilarModeThreshold]
 *   pozostałych, z przetasowanym radiem — kolejka nie kończy się nigdy, dopóki tryb jest włączony.
 *   Wcześniej siedział w `LaunchedEffect` w App.kt i przy zgaszonym ekranie stawał.
 */
class QueueContinuation(
    private val settings: PlaybackSettingsStore,
    /** Getter, bo repozytorium zmienia się z sesją Spotify i źródłem danych. */
    private val repository: () -> SpotifyRepository?,
    private val logger: NutaLogger,
) {
    private val similar = MutableStateFlow(false)
    val similarMode: StateFlow<Boolean> = similar.asStateFlow()

    fun setSimilarMode(on: Boolean) {
        similar.value = on
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun attach(players: Flow<AudioPlayer?>, scope: CoroutineScope) {
        scope.launch {
            players.collectLatest { player ->
                player ?: return@collectLatest
                combine(player.state, similar) { state, similarOn ->
                    Trigger(
                        index = state.currentIndex,
                        size = state.queue.size,
                        playing = state.status == PlayerStatus.PLAYING || state.status == PlayerStatus.LOADING,
                        similar = similarOn,
                    )
                }
                    .distinctUntilChanged()
                    .collectLatest { trigger -> extendIfNeeded(player, trigger) }
            }
        }
    }

    private data class Trigger(val index: Int, val size: Int, val playing: Boolean, val similar: Boolean)

    private suspend fun extendIfNeeded(player: AudioPlayer, trigger: Trigger) {
        if (trigger.size == 0) {
            // pusta kolejka kończy tryb podobnych — nie ma od czego liczyć radia
            similar.value = false
            return
        }
        if (trigger.index < 0) return
        val remaining = trigger.size - 1 - trigger.index
        val threshold = when {
            trigger.similar -> SimilarModeThreshold
            // Tylko w trakcie grania: przywrócona po starcie kolejka (IDLE na ostatnim utworze)
            // nie powinna sama rosnąć, zanim użytkownik cokolwiek włączy.
            trigger.playing && settings.settings.value.autoContinue -> 0
            else -> return
        }
        if (remaining > threshold) return
        val seed = player.state.value.currentTrack ?: return
        val repo = repository() ?: return
        try {
            val known = player.state.value.queue.mapTo(mutableSetOf()) { it.id }
            val radio = repo.getTrackRadio(seed)
            val additions = (if (trigger.similar) radio.shuffled() else radio).filter { known.add(it.id) }
            if (additions.isNotEmpty()) player.appendToQueue(additions)
            logger.info(
                "QueueContinuation", "queue_continued", "Dołożono podobne utwory do kolejki",
                fields = mapOf("seed" to seed.title, "added" to additions.size.toString(), "similarMode" to trigger.similar.toString()),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger.warn(
                "QueueContinuation", "queue_continue_failed", "Nie udało się dołożyć podobnych utworów",
                fields = mapOf("reason" to (error.message ?: error::class.simpleName.orEmpty())),
            )
        }
    }

    private companion object {
        const val SimilarModeThreshold = 3
    }
}
