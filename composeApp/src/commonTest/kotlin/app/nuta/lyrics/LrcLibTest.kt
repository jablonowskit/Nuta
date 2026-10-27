package app.nuta.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LrcLibTest {
    // Odpowiedź /api/get z curla (27.10.2026), przycięta do pól czytanych przez parser.
    private val realResponse = """
        {"id":62937,"trackName":"Enter Sandman","artistName":"Metallica","albumName":"Metallica","duration":331.0,
         "instrumental":false,"plainLyrics":"Say your prayers, little one\nDon't forget, my son",
         "syncedLyrics":"[01:12.25] Say your prayers, little one\n[01:14.09] Don't forget, my son"}
    """.trimIndent()

    @Test
    fun parsesPlainAndSyncedLyrics() {
        val lyrics = assertNotNull(LrcLibClient.parse(realResponse))
        assertEquals(false, lyrics.instrumental)
        assertEquals("Say your prayers, little one\nDon't forget, my son", lyrics.plain)
        val synced = assertNotNull(lyrics.synced)
        assertEquals(2, synced.size)
        assertEquals(LyricLine(72_250, "Say your prayers, little one"), synced[0])
        assertEquals(74_090, synced[1].timeMs)
    }

    @Test
    fun instrumentalWithoutTextIsStillAResult() {
        val lyrics = assertNotNull(LrcLibClient.parse("""{"instrumental":true,"plainLyrics":null,"syncedLyrics":null}"""))
        assertTrue(lyrics.instrumental)
        assertNull(lyrics.synced)
    }

    @Test
    fun garbageAndEmptyAnswersGiveNull() {
        assertNull(LrcLibClient.parse(""))
        assertNull(LrcLibClient.parse("<html>error</html>"))
        assertNull(LrcLibClient.parse("""{"instrumental":false,"plainLyrics":"","syncedLyrics":null}"""))
    }

    @Test
    fun lrcWithRepeatedTagsAndFractionsIsSorted() {
        val lines = LrcLibClient.parseLrc(
            """
            [ar:Metallica]
            [00:20.5]Druga
            [00:10.123][01:00.00]Refren
            [00:30]
            bez znacznika
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                LyricLine(10_123, "Refren"),
                LyricLine(20_500, "Druga"),
                LyricLine(30_000, ""),
                LyricLine(60_000, "Refren"),
            ),
            lines,
        )
    }
}
