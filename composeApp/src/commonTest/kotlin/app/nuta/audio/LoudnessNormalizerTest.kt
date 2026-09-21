package app.nuta.audio

import app.nuta.settings.LoudnessNormalization
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoudnessNormalizerTest {
    private fun normalizer(mode: LoudnessNormalization) =
        LoudnessNormalizer(sampleRateHz = 48_000, channelCount = 2, mode = mode)

    /** Amplituda sinusa o zadanym RMS w dBFS (RMS sinusa = amplituda / sqrt(2)). */
    private fun amplitudeForRms(dbfs: Double): Float = (10.0.pow(dbfs / 20.0) * sqrt(2.0)).toFloat()

    /** Przepuszcza [seconds] sekund sinusa i zwraca RMS wyjścia z drugiej połowy (po ustabilizowaniu). */
    private fun rmsAfterProcessing(mode: LoudnessNormalization, inputDbfs: Double, seconds: Double = 4.0): Double {
        val n = normalizer(mode)
        val amplitude = amplitudeForRms(inputDbfs)
        val total = (48_000 * 2 * seconds).toInt()
        var sum = 0.0
        var counted = 0
        for (i in 0 until total) {
            // 440 Hz, kanały przeplecione — dokładna faza nie ma znaczenia dla RMS
            val phase = 2.0 * kotlin.math.PI * 440.0 * (i / 2) / 48_000.0
            val out = n.processSample((amplitude * kotlin.math.sin(phase)).toFloat())
            if (i > total / 2) { sum += out.toDouble() * out.toDouble(); counted++ }
        }
        return 20 * log10(sqrt(sum / counted))
    }

    @Test
    fun offModeLeavesSamplesUntouched() {
        val n = normalizer(LoudnessNormalization.OFF)
        assertTrue(!n.active)
        listOf(-1f, -0.5f, 0f, 0.25f, 0.9f).forEach { assertEquals(it, n.processSample(it)) }
    }

    @Test
    fun quietTrackIsLeftUntouched() {
        // Regresja 19.09.2026: wcześniej cichy utwór (-30 dBFS) był wzmacniany w stronę celu
        // (-16 dBFS). Zgłoszone na słuch: wzmacnianie ciszy w połączeniu z tłumieniem szczytów
        // dawało słyszalne "pompowanie" w rytm utworu. maxBoostDb=0 usuwa wzmacnianie całkowicie
        // — cichy sygnał ma zostać dokładnie taki, jaki był, niezależnie od trybu.
        val out = rmsAfterProcessing(LoudnessNormalization.NORMAL, inputDbfs = -30.0)
        assertTrue(abs(out - -30.0) < 0.5, "cichy sygnał nie powinien być ruszany, wyszło $out dBFS")
    }

    @Test
    fun loudTrackIsAttenuatedTowardsTarget() {
        // To jest różnica wobec LoudnessEnhancer, który potrafił tylko wzmacniać.
        val out = rmsAfterProcessing(LoudnessNormalization.NORMAL, inputDbfs = -6.0)
        assertTrue(out < -6.0 - 3, "oczekiwano ściszenia, wyszło $out dBFS")
    }

    @Test
    fun loudAndQuietEndUpCloserTogether() {
        // Rozrzut maleje tylko od strony głośnej (tłumienie) — cicha strona zostaje bez zmian
        // od 19.09.2026 (patrz quietTrackIsLeftUntouched), więc zbliżenie jest połowiczne
        // względem starego zachowania, ale nadal wyraźne.
        val quiet = rmsAfterProcessing(LoudnessNormalization.NORMAL, inputDbfs = -28.0)
        val loud = rmsAfterProcessing(LoudnessNormalization.NORMAL, inputDbfs = -8.0)
        val spreadBefore = 20.0
        val spreadAfter = abs(loud - quiet)
        assertTrue(spreadAfter < spreadBefore, "rozrzut miał zmaleć: $spreadBefore -> $spreadAfter dB")
        assertTrue(abs(quiet - -28.0) < 0.5, "cicha strona nie powinna się ruszyć, wyszło $quiet dBFS")
    }

    @Test
    fun gentleModeAttenuatesLessThanNormal() {
        // GENTLE ma maxAttenuationDb=-6, NORMAL=-12 — dla bardzo głośnego sygnału (-4 dBFS,
        // wymagającego więcej niż -6 dB korekty do celu) NORMAL musi ściszyć mocniej.
        // (Nie testujemy już na cichym sygnale — maxBoostDb=0 w obu trybach, więc oba
        // zostawiłyby go bez zmian i test niczego by nie odróżniał, patrz quietTrackIsLeftUntouched.)
        val gentle = rmsAfterProcessing(LoudnessNormalization.GENTLE, inputDbfs = -4.0)
        val normal = rmsAfterProcessing(LoudnessNormalization.NORMAL, inputDbfs = -4.0)
        assertTrue(normal < gentle, "NORMAL ($normal) powinien ściszyć mocniej niż GENTLE ($gentle)")
    }

    @Test
    fun outputNeverExceedsCeiling() {
        val n = normalizer(LoudnessNormalization.NORMAL)
        // Sygnał na pełnej skali plus próba wypchnięcia poza zakres.
        repeat(48_000 * 2) { i ->
            val out = n.processSample(if (i % 2 == 0) 1f else -1f)
            assertTrue(abs(out) <= LoudnessNormalizer.Ceiling + 1e-6f, "próbka $out przekroczyła sufit")
        }
    }

    @Test
    fun resetClearsAttenuationCarriedFromPreviousTrack() {
        // Media3 woła flush() przy seeku i zmianie utworu. Bez reset() tłumienie wyliczone dla
        // poprzedniego, głośnego materiału obowiązywało dalej, więc początek kolejnego utworu
        // był słyszalnie za cichy (schodziło dopiero przez ReleaseMs).
        val n = normalizer(LoudnessNormalization.NORMAL)
        val amplitude = amplitudeForRms(-10.0)
        // Sinus, nie stała: DC o tej amplitudzie wpadłby w limiter i mierzylibyśmy obcięcie
        // sufitem zamiast wypracowanego tłumienia.
        fun sampleAt(i: Int) =
            (amplitude * kotlin.math.sin(2.0 * kotlin.math.PI * 440.0 * (i / 2) / 48_000.0)).toFloat()
        repeat(48_000 * 2) { n.processSample(sampleAt(it)) }

        // Szczyt sinusa po ustabilizowaniu — normalizator tłumi materiał głośniejszy niż cel.
        val probe = amplitude
        val attenuated = n.processSample(probe)
        assertTrue(attenuated < probe * 0.9f, "spodziewane tłumienie po głośnym utworze, wyszło $attenuated")

        n.reset()
        // Zaraz po resecie gain wraca do 1.0, więc ta sama próbka przechodzi bez tłumienia.
        assertTrue(
            abs(n.processSample(probe) - probe) < probe * 0.01f,
            "po reset() gain miał wrócić do 1.0",
        )
    }

    @Test
    fun silenceIsNotAmplifiedIntoNoise() {
        val n = normalizer(LoudnessNormalization.NORMAL)
        // Bardzo cicha próbka (-60 dBFS, poniżej progu) nie powinna być windowana do celu.
        val quiet = 10.0.pow(-60.0 / 20.0).toFloat()
        var maxOut = 0f
        repeat(48_000 * 2) { maxOut = maxOf(maxOut, abs(n.processSample(quiet))) }
        // gain startuje z 1.0 i przy sygnale pod progiem nie rośnie
        assertTrue(maxOut <= quiet * 1.01f, "cisza została wzmocniona do $maxOut")
    }
}
