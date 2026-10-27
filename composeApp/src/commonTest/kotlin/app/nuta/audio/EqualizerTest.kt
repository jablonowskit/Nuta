package app.nuta.audio

import app.nuta.settings.EqualizerPreset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EqualizerTest {
    private val rate = 48_000

    /** Wzmocnienie [preset] dla sinusa [hz] w dB (RMS z drugiej sekundy — po ustaleniu filtrów). */
    private fun gainDb(preset: EqualizerPreset, hz: Double, amplitude: Float = 0.5f): Double {
        val eq = Equalizer(rate, 1, preset)
        var inSq = 0.0
        var outSq = 0.0
        repeat(rate * 2) { i ->
            val x = amplitude * sin(2 * PI * hz * i / rate).toFloat()
            val y = eq.processSample(x, 0)
            if (i >= rate) { inSq += x * x; outSq += y * y }
        }
        return 20 * log10(sqrt(outSq) / sqrt(inSq))
    }

    @Test
    fun offPassesSamplesUntouched() {
        val eq = Equalizer(rate, 2, EqualizerPreset.OFF)
        listOf(0.3f, -0.7f, 1f).forEachIndexed { i, x -> assertEquals(x, eq.processSample(x, i % 2)) }
    }

    @Test
    fun bassPresetLiftsLowsRelativeToHighs() {
        val low = gainDb(EqualizerPreset.BASS, 50.0)
        val high = gainDb(EqualizerPreset.BASS, 5_000.0)
        // półka +6 dB, a całość ściszona o 6 dB zapasu: bas ~0 dB, góra ~-6 dB
        assertTrue(low - high > 5.0, "różnica bas/góra: ${low - high} dB")
        assertTrue(abs(low) < 1.0, "bas po zapasie powinien zostać w okolicy 0 dB, jest $low")
    }

    @Test
    fun vocalPresetLiftsPresenceBand() {
        assertTrue(gainDb(EqualizerPreset.VOCAL, 2_500.0) - gainDb(EqualizerPreset.VOCAL, 60.0) > 5.0)
    }

    @Test
    fun nightPresetCutsBass() {
        assertTrue(gainDb(EqualizerPreset.NIGHT, 50.0) < -6.0)
    }

    @Test
    fun boostedPresetDoesNotPushFullScaleSignalOverTheTop() {
        // pełna skala w środku podbitego pasma — zapas (preGain) musi utrzymać szczyty w zakresie
        val eq = Equalizer(rate, 1, EqualizerPreset.BASS)
        var peak = 0f
        repeat(rate) { i -> peak = maxOf(peak, abs(eq.processSample(sin(2 * PI * 60.0 * i / rate).toFloat(), 0))) }
        assertTrue(peak <= 1.02f, "szczyt $peak")
    }

    @Test
    fun ffmpegChainMatchesBands() {
        assertEquals(emptyList(), Equalizer.ffmpegFilters(EqualizerPreset.OFF))
        assertEquals(listOf("volume=-6dB", "bass=g=6:f=100"), Equalizer.ffmpegFilters(EqualizerPreset.BASS))
        assertEquals(
            listOf("volume=-4dB", "bass=g=-3:f=120", "equalizer=f=2500:t=q:w=1:g=4"),
            Equalizer.ffmpegFilters(EqualizerPreset.VOCAL),
        )
    }
}
