package app.nuta.player

import app.nuta.core.models.PlayerState
import app.nuta.core.models.Track
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class FilePlaybackQueueStoreTest {
    @Test
    fun roundTripSavesQueueIndexAndPosition() {
        val dir = Files.createTempDirectory("nuta-queue-test")
        val store = FilePlaybackQueueStore(dir.resolve("playback-queue.json"))
        val track = Track("mbid-1", "Title", listOf("Artist"), "Album", 120_000)
        store.save(PlayerState(queue = listOf(track), currentIndex = 0, positionMs = 45_000))
        val loaded = store.load()
        assertEquals(1, loaded.queue.size)
        assertEquals("mbid-1", loaded.queue[0].id)
        assertEquals(0, loaded.currentIndex)
        assertEquals(45_000, loaded.positionMs)
    }
}
