package app.nuta.player

import app.nuta.core.models.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LongTrackPositionsTest {
    private var stored = ""
    private fun positions() = LongTrackPositions(load = { stored }, save = { stored = it })
    private val mix = Track("mix", "Essential Mix", listOf("DJ"), "", 7_200_000)
    private val song = Track("song", "Song", listOf("Band"), "", 240_000)

    @Test
    fun remembersLongTrackAcrossInstances() {
        positions().remember(mix, 1_800_000, mix.durationMs)
        assertEquals(1_800_000, positions().positionFor(mix))
    }

    @Test
    fun ignoresShortTracks() {
        positions().remember(song, 120_000, song.durationMs)
        assertNull(positions().positionFor(song))
    }

    @Test
    fun startOrEndClearsEntry() {
        val p = positions()
        p.remember(mix, 1_800_000, mix.durationMs)
        p.remember(mix, 5_000, mix.durationMs)
        assertNull(p.positionFor(mix))
        p.remember(mix, 1_800_000, mix.durationMs)
        p.remember(mix, mix.durationMs - 10_000, mix.durationMs)
        assertNull(p.positionFor(mix))
    }

    @Test
    fun forgetAndEviction() {
        val p = positions()
        p.remember(mix, 1_800_000, mix.durationMs)
        p.forget(mix)
        assertNull(positions().positionFor(mix))
        repeat(LongTrackPositions.MaxEntries + 1) { i -> p.remember(Track("t$i", "T", emptyList(), "", 3_600_000), 60_000, 3_600_000) }
        assertNull(positions().positionFor(Track("t0", "T", emptyList(), "", 3_600_000)))
        assertEquals(60_000, positions().positionFor(Track("t${LongTrackPositions.MaxEntries}", "T", emptyList(), "", 3_600_000)))
    }
}
