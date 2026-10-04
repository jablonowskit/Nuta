package app.nuta.data

import app.nuta.core.models.Track
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileLikedTracksCacheTest {

    @Test
    fun loadReturnsEmptyWhenUsernameMismatches() {
        val path = Files.createTempFile("liked-tracks-", ".json")
        try {
            val cache = FileLikedTracksCache(path)
            val track = Track("id1", "Title", listOf("Artist"), "Album", 1000L)
            cache.save("alice", listOf(track))
            assertEquals(1, cache.load("alice").size)
            assertTrue(cache.load("bob").isEmpty())
            assertTrue(cache.load("").isEmpty())
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(path.resolveSibling("${path.fileName}.tmp"))
        }
    }

    @Test
    fun saveOverwritesUsernameScope() {
        val path = Files.createTempFile("liked-tracks-", ".json")
        try {
            val cache = FileLikedTracksCache(path)
            cache.save("alice", listOf(Track("a", "A", emptyList(), "", 1L)))
            cache.save("bob", listOf(Track("b", "B", emptyList(), "", 2L)))
            assertTrue(cache.load("alice").isEmpty())
            assertEquals("b", cache.load("bob").single().id)
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(path.resolveSibling("${path.fileName}.tmp"))
        }
    }
}
