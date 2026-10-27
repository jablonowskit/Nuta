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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Gdy gra ostatni utwór kolejki, dokłada podobne (radio od tego utworu) — muzyka nie staje
 * na końcu playlisty. Działa poza UI, tak jak [SleepTimer]: efekty Compose nie dostają klatek
 * przy wygaszonym ekranie, a właśnie wtedy kolejka najczęściej dobiega końca.
 *
 * Wyłączane ustawieniem `autoContinue`. Tryb „podobnych” (⇄) dokłada wcześniej, przy ≤3
 * pozostałych — wtedy tutaj zwykle nic się nie dzieje, a duplikaty i tak są odfiltrowane.
 */
class QueueContinuation(
    private val settings: PlaybackSettingsStore,
    /** Getter, bo repozytorium zmienia się z sesją Spotify i źródłem danych. */
    private val repository: () -> SpotifyRepository?,
    private val logger: NutaLogger,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun attach(players: Flow<AudioPlayer?>, scope: CoroutineScope) {
        scope.launch {
            players.collectLatest { player ->
                player ?: return@collectLatest
                player.state
                    .map { Trigger(it.currentIndex, it.queue.size, it.status == PlayerStatus.PLAYING || it.status == PlayerStatus.LOADING) }
                    .distinctUntilChanged()
                    .collectLatest { trigger -> extendIfAtEnd(player, trigger) }
            }
        }
    }

    private data class Trigger(val index: Int, val size: Int, val playing: Boolean)

    private suspend fun extendIfAtEnd(player: AudioPlayer, trigger: Trigger) {
        // Tylko w trakcie grania: przywrócona po starcie kolejka (IDLE na ostatnim utworze)
        // nie powinna sama rosnąć, zanim użytkownik cokolwiek włączy.
        if (!trigger.playing || trigger.size == 0 || trigger.index != trigger.size - 1) return
        if (!settings.settings.value.autoContinue) return
        val seed = player.state.value.currentTrack ?: return
        val repo = repository() ?: return
        try {
            val known = player.state.value.queue.mapTo(mutableSetOf()) { it.id }
            val additions = repo.getTrackRadio(seed).filter { known.add(it.id) }
            if (additions.isNotEmpty()) player.appendToQueue(additions)
            logger.info(
                "QueueContinuation", "queue_continued", "Dołożono podobne utwory na końcu kolejki",
                fields = mapOf("seed" to seed.title, "added" to additions.size.toString()),
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
}
