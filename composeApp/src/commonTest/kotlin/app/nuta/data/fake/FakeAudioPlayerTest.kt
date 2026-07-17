package app.nuta.data.fake

import app.nuta.core.logging.MemoryLogger
import app.nuta.core.models.PlayerStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class FakeAudioPlayerTest {
    @Test
    fun queueNavigationStaysWithinBounds() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val logger = MemoryLogger(now = { "test" })
        val player = FakeAudioPlayer(scope, logger)

        player.setQueue(DemoLibrary.tracks.take(2))
        player.previous()
        assertEquals(0, player.state.value.currentIndex)
        player.next()
        player.next()
        assertEquals(1, player.state.value.currentIndex)
        player.play()
        assertEquals(PlayerStatus.PLAYING, player.state.value.status)
        player.pause()
        assertEquals(PlayerStatus.PAUSED, player.state.value.status)
        scope.cancel()
    }
}
