package app.nuta.audio

import app.nuta.settings.EqualizerPreset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Korektor na presetach — liczony na próbkach, obok [LoudnessNormalizer], z tego samego powodu:
 * systemowe efekty audio (`android.media.audiofx`) na części urządzeń się nie inicjalizują
 * (Galaxy A55: `Error -3` dla LoudnessEnhancer), a własne filtry działają wszędzie tak samo.
 *
 * Filtry biquad z „Audio EQ Cookbook” (R. Bristow-Johnson): półka niskich, szczyt środka, półka
 * wysokich. Przed filtrami ściszamy o największe podbicie presetu ([preGainDb]) — inaczej podbity
 * bas wybijałby głośne fragmenty ponad pełną skalę i twardo by się obcinał.
 *
 * Desktop nie liczy próbek sam (mpv/ffmpeg) — [ffmpegFilters] daje te same pasma jako filtry
 * ffmpeg, więc preset brzmi tak samo na obu platformach.
 */
class Equalizer(sampleRateHz: Int, private val channelCount: Int, preset: EqualizerPreset) {
    private val bands: List<Band> = bandsFor(preset)
    private val preGain: Float = 10.0.pow(preGainDb(preset) / 20.0).toFloat()
    private val filters: List<Array<Biquad>> = bands.map { band -> Array(channelCount) { Biquad.of(band, sampleRateHz) } }

    val active: Boolean get() = bands.isNotEmpty()

    /** [channel] = indeks kanału próbki przeplecionej (0 = lewy, 1 = prawy, ...). */
    fun processSample(sample: Float, channel: Int): Float {
        if (!active) return sample
        var value = (sample * preGain).toDouble()
        for (perChannel in filters) value = perChannel[channel % channelCount].process(value)
        return value.toFloat()
    }

    /** Seek/nowy utwór: stan filtrów z poprzedniego materiału dałby krótki trzask. */
    fun reset() = filters.forEach { perChannel -> perChannel.forEach(Biquad::reset) }

    internal enum class Kind { LOW_SHELF, PEAK, HIGH_SHELF }

    internal data class Band(val kind: Kind, val frequencyHz: Double, val gainDb: Double, val q: Double = 0.9)

    private class Biquad(
        private val b0: Double, private val b1: Double, private val b2: Double,
        private val a1: Double, private val a2: Double,
    ) {
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }

        fun reset() { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 }

        companion object {
            fun of(band: Band, sampleRateHz: Int): Biquad {
                val a = 10.0.pow(band.gainDb / 40.0)
                val w0 = 2 * PI * band.frequencyHz / sampleRateHz
                val cosW = cos(w0)
                val sinW = sin(w0)
                val b0: Double; val b1: Double; val b2: Double; val a0: Double; val a1: Double; val a2: Double
                when (band.kind) {
                    Kind.PEAK -> {
                        val alpha = sinW / (2 * band.q)
                        b0 = 1 + alpha * a; b1 = -2 * cosW; b2 = 1 - alpha * a
                        a0 = 1 + alpha / a; a1 = -2 * cosW; a2 = 1 - alpha / a
                    }
                    Kind.LOW_SHELF -> {
                        val twoSqrtAAlpha = 2 * sqrt(a) * sinW / 2 * sqrt(2.0)
                        b0 = a * ((a + 1) - (a - 1) * cosW + twoSqrtAAlpha)
                        b1 = 2 * a * ((a - 1) - (a + 1) * cosW)
                        b2 = a * ((a + 1) - (a - 1) * cosW - twoSqrtAAlpha)
                        a0 = (a + 1) + (a - 1) * cosW + twoSqrtAAlpha
                        a1 = -2 * ((a - 1) + (a + 1) * cosW)
                        a2 = (a + 1) + (a - 1) * cosW - twoSqrtAAlpha
                    }
                    Kind.HIGH_SHELF -> {
                        val twoSqrtAAlpha = 2 * sqrt(a) * sinW / 2 * sqrt(2.0)
                        b0 = a * ((a + 1) + (a - 1) * cosW + twoSqrtAAlpha)
                        b1 = -2 * a * ((a - 1) + (a + 1) * cosW)
                        b2 = a * ((a + 1) + (a - 1) * cosW - twoSqrtAAlpha)
                        a0 = (a + 1) - (a - 1) * cosW + twoSqrtAAlpha
                        a1 = 2 * ((a - 1) - (a + 1) * cosW)
                        a2 = (a + 1) - (a - 1) * cosW - twoSqrtAAlpha
                    }
                }
                return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }
        }
    }

    companion object {
        internal fun bandsFor(preset: EqualizerPreset): List<Band> = when (preset) {
            EqualizerPreset.OFF -> emptyList()
            EqualizerPreset.BASS -> listOf(Band(Kind.LOW_SHELF, 100.0, 6.0))
            // środek przytłumiony basem, podbicie w paśmie zrozumiałości głosu
            EqualizerPreset.VOCAL -> listOf(Band(Kind.LOW_SHELF, 120.0, -3.0), Band(Kind.PEAK, 2_500.0, 4.0, q = 1.0))
            EqualizerPreset.BRIGHT -> listOf(Band(Kind.HIGH_SHELF, 8_000.0, 5.0))
            // cicho w nocy: mniej dudnienia przez ścianę, wokal czytelny bez podgłaśniania
            EqualizerPreset.NIGHT -> listOf(Band(Kind.LOW_SHELF, 150.0, -8.0), Band(Kind.PEAK, 2_500.0, 2.0, q = 1.0))
        }

        /** Zapas na podbicia — preset nigdy nie podnosi szczytów ponad oryginał. */
        internal fun preGainDb(preset: EqualizerPreset): Double = -(bandsFor(preset).maxOfOrNull { it.gainDb } ?: 0.0).coerceAtLeast(0.0)

        /** Te same pasma jako filtry ffmpeg (desktop: łańcuch `af` w mpv). Pusta lista dla OFF. */
        fun ffmpegFilters(preset: EqualizerPreset): List<String> {
            val bands = bandsFor(preset)
            if (bands.isEmpty()) return emptyList()
            val pre = preGainDb(preset)
            return buildList {
                if (pre < 0) add("volume=${fmt(pre)}dB")
                bands.forEach { band ->
                    add(
                        when (band.kind) {
                            Kind.LOW_SHELF -> "bass=g=${fmt(band.gainDb)}:f=${band.frequencyHz.toInt()}"
                            Kind.HIGH_SHELF -> "treble=g=${fmt(band.gainDb)}:f=${band.frequencyHz.toInt()}"
                            Kind.PEAK -> "equalizer=f=${band.frequencyHz.toInt()}:t=q:w=${fmt(band.q)}:g=${fmt(band.gainDb)}"
                        },
                    )
                }
            }
        }

        private fun fmt(value: Double): String = if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    }
}
