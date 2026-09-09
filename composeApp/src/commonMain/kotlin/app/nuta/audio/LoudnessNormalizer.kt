package app.nuta.audio

import app.nuta.settings.LoudnessNormalization
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Normalizacja głośności działająca na sygnale: mierzy bieżący poziom RMS i dąży do wspólnego
 * poziomu docelowego, więc **ścisza głośne utwory i podnosi ciche** — w przeciwieństwie do
 * `android.media.audiofx.LoudnessEnhancer`, który potrafi tylko wzmacniać (i którego część
 * urządzeń, m.in. Galaxy A55, nie inicjalizuje wcale — `Error: -3`).
 *
 * Czysta logika, bez zależności od Androida ani mpv, żeby dała się przetestować jednostkowo;
 * platformowe opakowanie (`AudioProcessor` w Media3) tylko podaje jej próbki.
 *
 * Algorytm (jednoprzebiegowy, bo strumień jest odtwarzany na żywo — nie znamy go z góry):
 * 1. RMS liczony w oknie ~400 ms, tak jak „momentary loudness" w EBU R128.
 * 2. Z RMS wynika wzmocnienie potrzebne do trafienia w cel, ograniczone do [maxAttenuation,
 *    maxBoost] — bez tego cisza między utworami byłaby wzmacniana do szumu.
 * 3. Wzmocnienie zmienia się płynnie (attack/release), żeby nie „pompowało" na perkusji.
 * 4. Limiter twardo przycina próbki przekraczające [Ceiling], co chroni przed przesterowaniem
 *    po wzmocnieniu.
 *
 * Świadomie NIE jest to pełne EBU R128 (brak K-weightingu i gatingu): różnica jest słyszalna
 * głównie w materiale o dużej dynamice, a koszt to kilkaset linii DSP. Desktop używa
 * ffmpegowego `loudnorm` przez mpv, więc parytet między platformami jest funkcjonalny
 * („obie wyrównują głośność"), nie identyczny próbka w próbkę.
 */
class LoudnessNormalizer(
    private val sampleRateHz: Int,
    /** Liczba kanałów — okno pomiaru liczymy w próbkach przeplecionych, więc skaluje jego długość. */
    channelCount: Int,
    mode: LoudnessNormalization,
) {
    /** Poziom docelowy w dBFS. Odpowiada celom `loudnorm` na desktopie (I=-16 / I=-20 LUFS). */
    private val targetDbfs: Double = when (mode) {
        LoudnessNormalization.OFF -> 0.0
        LoudnessNormalization.GENTLE -> -20.0
        LoudnessNormalization.NORMAL -> -16.0
    }

    /** GENTLE koryguje łagodniej — mniejszy zakres ingerencji w oryginalne proporcje utworu. */
    private val maxBoostDb: Double = if (mode == LoudnessNormalization.GENTLE) 6.0 else 12.0
    private val maxAttenuationDb: Double = if (mode == LoudnessNormalization.GENTLE) -6.0 else -12.0

    private val windowSamples: Int = (sampleRateHz * WindowMs / 1000).coerceAtLeast(1) * channelCount
    private var sumOfSquares = 0.0
    private var samplesInWindow = 0
    /** Wzmocnienie liniowe stosowane teraz; 1.0 = brak zmiany. */
    private var currentGain = 1.0
    private var targetGain = 1.0

    val active: Boolean get() = targetDbfs != 0.0

    /**
     * Przetwarza jedną próbkę znormalizowaną do [-1, 1] i zwraca próbkę wyjściową.
     * Wywoływane per próbka, bo `AudioProcessor` dostaje bufor PCM bez podziału na kanały.
     */
    fun processSample(sample: Float): Float {
        if (!active) return sample
        accumulate(sample)
        currentGain = approach(currentGain, targetGain)
        return limit(sample * currentGain.toFloat())
    }

    private fun accumulate(sample: Float) {
        sumOfSquares += sample.toDouble() * sample.toDouble()
        samplesInWindow++
        if (samplesInWindow < windowSamples) return
        val rms = sqrt(sumOfSquares / samplesInWindow)
        sumOfSquares = 0.0
        samplesInWindow = 0
        targetGain = gainFor(rms)
    }

    /**
     * Wzmocnienie liniowe potrzebne, by sygnał o podanym [rms] trafił w [targetDbfs].
     * Fragmenty cichsze niż [SilenceFloorDbfs] zostawiamy bez zmian: to przerwy między
     * utworami i wybrzmienia, których wzmacnianie tylko podniosłoby szum.
     */
    internal fun gainFor(rms: Double): Double {
        if (rms <= 0.0) return currentGain
        val rmsDbfs = 20 * log10(rms)
        if (rmsDbfs < SilenceFloorDbfs) return currentGain
        val neededDb = (targetDbfs - rmsDbfs).coerceIn(maxAttenuationDb, maxBoostDb)
        return 10.0.pow(neededDb / 20.0)
    }

    /**
     * Płynne dojście do docelowego wzmocnienia. Ściszamy szybciej niż wzmacniamy (attack
     * krótszy od release), bo nagły głośny fragment trzeba opanować od razu, a zbyt szybkie
     * wzmacnianie ciszy słychać jako „pompowanie".
     */
    private fun approach(from: Double, to: Double): Double {
        val stepMs = 1000.0 / sampleRateHz
        val timeConstantMs = if (to < from) AttackMs else ReleaseMs
        val step = (stepMs / timeConstantMs).coerceIn(0.0, 1.0)
        return from + (to - from) * step
    }

    /** Twarde ograniczenie do sufitu — po wzmocnieniu szczyty mogłyby wyjść poza zakres. */
    private fun limit(sample: Float): Float =
        if (abs(sample) <= Ceiling) sample else if (sample > 0) Ceiling else -Ceiling

    internal companion object {
        /** Okno pomiaru ~400 ms — tyle samo, ile „momentary loudness" w EBU R128. */
        const val WindowMs = 400
        /** Poniżej tego poziomu nie korygujemy (przerwy, wybrzmienia). */
        const val SilenceFloorDbfs = -50.0
        /** -1.5 dBFS, ten sam sufit co `TP=-1.5` w desktopowym loudnorm. */
        const val Ceiling = 0.84f
        const val AttackMs = 50.0
        const val ReleaseMs = 400.0
    }
}
