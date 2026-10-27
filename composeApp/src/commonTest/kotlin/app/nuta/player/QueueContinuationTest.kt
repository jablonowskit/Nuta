package app.nuta.player

import app.nuta.core.logging.MemoryLogger
import app.nuta.core.models.Track
import app.nuta.data.fake.FakeAudioPlayer
import app.nuta.data.fake.FakeSpotifyRepository
import app.nuta.settings.InMemoryPlaybackSettingsStore
import app.nuta.settings.YouTubePlaybackSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueueContinuationTest {
    private val logger = MemoryLogger(now = { "test" })
    private val seed = Track("seed", "Seed", listOf("Artist"), "", 100_000)

    private fun run(autoContinue: Boolean, play: Boolean): Int = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val player = FakeAudioPlayer(scope, logger)
        val settings = InMemoryPlaybackSettingsStore(YouTubePlaybackSettings(autoContinue = autoContinue))
        QueueContinuation(settings, { FakeSpotifyRepository(logger) }, logger).attach(flowOf(player), scope)
        player.setQueue(listOf(seed))
        if (play) player.play()
        delay(500)
        scope.cancel()
        player.state.value.queue.size
    }

    @Test
    fun appendsSimilarTracksWhenLastTrackPlays() {
        assertTrue(run(autoContinue = true, play = true) > 1)
    }

    @Test
    fun doesNothingWhenDisabled() {
        assertEquals(1, run(autoContinue = false, play = true))
    }

    @Test
    fun restoredQueueDoesNotGrowBeforePlayback() {
        assertEquals(1, run(autoContinue = true, play = false))
    }

    private fun runMidQueue(similar: Boolean): Int = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val player = FakeAudioPlayer(scope, logger)
        val settings = InMemoryPlaybackSettingsStore(YouTubePlaybackSettings(autoContinue = true))
        val continuation = QueueContinuation(settings, { FakeSpotifyRepository(logger) }, logger)
        continuation.attach(flowOf(player), scope)
        // 3 utwory, gra pierwszy: zostały 2 — tryb podobnych (próg 3) dokłada, „Graj dalej” jeszcze nie.
        player.setQueue(listOf(seed, Track("b", "B", listOf("X"), "", 1), Track("c", "C", listOf("X"), "", 1)))
        continuation.setSimilarMode(similar)
        player.play()
        delay(500)
        scope.cancel()
        player.state.value.queue.size
    }

    @Test
    fun similarModeExtendsBeforeLastTrack() {
        assertTrue(runMidQueue(similar = true) > 3)
    }

    @Test
    fun withoutSimilarModeMidQueueStaysUntouched() {
        assertEquals(3, runMidQueue(similar = false))
    }

    @Test
    fun emptyQueueTurnsSimilarModeOff() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val player = FakeAudioPlayer(scope, logger)
        val continuation = QueueContinuation(InMemoryPlaybackSettingsStore(), { FakeSpotifyRepository(logger) }, logger)
        continuation.attach(flowOf(player), scope)
        continuation.setSimilarMode(true)
        delay(300)
        scope.cancel()
        assertEquals(false, continuation.similarMode.value)
    }
}
