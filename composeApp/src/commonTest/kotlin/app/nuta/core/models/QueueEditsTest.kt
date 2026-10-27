package app.nuta.core.models

import kotlin.test.Test
import kotlin.test.assertEquals

class QueueEditsTest {
    private fun track(id: String) = Track(id, id, listOf("A"), "", 0, null)
    private fun ids(state: PlayerState) = state.queue.joinToString("") { it.id }

    private val abcd = PlayerState(queue = listOf("a", "b", "c", "d").map(::track), currentIndex = 1)

    @Test
    fun insertNextGoesRightAfterCurrentTrack() {
        val result = abcd.withInsertedNext(listOf(track("x"), track("y")))
        assertEquals("abxycd", ids(result))
        assertEquals(1, result.currentIndex)
    }

    @Test
    fun insertNextIntoEmptyQueue() {
        val result = PlayerState().withInsertedNext(listOf(track("x")))
        assertEquals("x", ids(result))
        assertEquals(-1, result.currentIndex)
    }

    @Test
    fun movingCurrentTrackFollowsIt() {
        val result = abcd.withMoved(from = 1, to = 3)
        assertEquals("acdb", ids(result))
        assertEquals(3, result.currentIndex)
        assertEquals("b", result.currentTrack?.id)
    }

    @Test
    fun movingTrackFromBeforeToAfterCurrentShiftsIndexDown() {
        val result = abcd.withMoved(from = 0, to = 2)
        assertEquals("bcad", ids(result))
        assertEquals("b", result.currentTrack?.id)
    }

    @Test
    fun movingTrackFromAfterToBeforeCurrentShiftsIndexUp() {
        val result = abcd.withMoved(from = 3, to = 0)
        assertEquals("dabc", ids(result))
        assertEquals("b", result.currentTrack?.id)
    }

    @Test
    fun movingBetweenUpcomingTracksKeepsIndex() {
        val result = abcd.withMoved(from = 3, to = 2)
        assertEquals("abdc", ids(result))
        assertEquals(1, result.currentIndex)
    }

    @Test
    fun invalidMoveIsNoOp() {
        assertEquals(abcd, abcd.withMoved(from = 1, to = 9))
        assertEquals(abcd, abcd.withMoved(from = 2, to = 2))
    }
}
