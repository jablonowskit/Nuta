package app.nuta.youtube

import app.nuta.settings.LoudnessNormalization
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoudnessGainTest {
    private fun assertClose(expected: Double, actual: Float) =
        assertTrue(abs(expected - actual) < 0.001, "oczekiwano ~$expected, było $actual")

    @Test
    fun `tryb wylaczony nie zmienia glosnosci`() {
        assertEquals(1f, LoudnessGain.volumeFor(9.0, LoudnessNormalization.OFF))
    }

    @Test
    fun `brak danych z YouTube nie zmienia glosnosci`() {
        assertEquals(1f, LoudnessGain.volumeFor(null, LoudnessNormalization.NORMAL))
    }

    @Test
    fun `utwor cichszy od odniesienia zostaje bez zmian`() {
        // podbicie wymagałoby wzmacniacza i groziło przesterowaniem — świadomie tylko ściszamy
        assertEquals(1f, LoudnessGain.volumeFor(-4.0, LoudnessNormalization.NORMAL))
    }

    @Test
    fun `pelne wyrownanie sciszia o cala roznice`() {
        // -6 dB to połowa amplitudy
        assertClose(0.5012, LoudnessGain.volumeFor(6.0, LoudnessNormalization.NORMAL))
    }

    @Test
    fun `lagodne wyrownanie koryguje polowe roznicy`() {
        assertClose(0.7079, LoudnessGain.volumeFor(6.0, LoudnessNormalization.GENTLE))
    }

    @Test
    fun `skrajnie glosny utwor nie schodzi ponizej limitu`() {
        // 40 dB obcięte do 12 dB w NORMAL i do 6 dB w GENTLE
        assertClose(0.2512, LoudnessGain.volumeFor(40.0, LoudnessNormalization.NORMAL))
        assertClose(0.5012, LoudnessGain.volumeFor(40.0, LoudnessNormalization.GENTLE))
    }
}
