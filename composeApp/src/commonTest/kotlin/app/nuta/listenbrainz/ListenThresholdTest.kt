package app.nuta.listenbrainz

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListenThresholdTest {
    @Test
    fun requiresHalfOfShortTrack() {
        val threeMinutes = 3 * 60 * 1000L
        assertEquals(90_000L, ListenThreshold.requiredMs(threeMinutes))
        assertFalse(ListenThreshold.isReached(positionMs = 89_999, durationMs = threeMinutes))
        assertTrue(ListenThreshold.isReached(positionMs = 90_000, durationMs = threeMinutes))
    }

    @Test
    fun capsLongTrackAtFourMinutes() {
        // Godzinny set: połowa to 30 minut, ale reguła ListenBrainz zalicza go już po 4 minutach.
        val oneHour = 60 * 60 * 1000L
        assertEquals(ListenThreshold.AbsoluteThresholdMs, ListenThreshold.requiredMs(oneHour))
        assertTrue(ListenThreshold.isReached(positionMs = 4 * 60 * 1000L, durationMs = oneHour))
        assertFalse(ListenThreshold.isReached(positionMs = 3 * 60 * 1000L, durationMs = oneHour))
    }

    @Test
    fun fallsBackToAbsoluteThresholdWhenDurationUnknown() {
        // durationMs = 0 zdarza się realnie: wpisy ListenBrainz mające tylko MSID oraz wyniki
        // MusicBrainz bez pola `length`. Bez znanej długości zostaje sam próg 4 minut.
        assertEquals(ListenThreshold.AbsoluteThresholdMs, ListenThreshold.requiredMs(0))
        assertFalse(ListenThreshold.isReached(positionMs = 60_000, durationMs = 0))
        assertTrue(ListenThreshold.isReached(positionMs = ListenThreshold.AbsoluteThresholdMs, durationMs = 0))
    }

    @Test
    fun rejectsTracksShorterThanClientMinimum() {
        // 20-sekundowy jingiel: nawet odsłuchany do końca nie kwalifikuje się do zgłoszenia.
        // To nasza decyzja, nie ograniczenie API — serwis takie wpisy przyjmuje.
        assertFalse(ListenThreshold.isReached(positionMs = 20_000, durationMs = 20_000))
        // Dokładnie 30 s to już akceptowalna długość — zaliczana po połowie.
        assertTrue(ListenThreshold.isReached(positionMs = 15_000, durationMs = 30_000))
    }
}
