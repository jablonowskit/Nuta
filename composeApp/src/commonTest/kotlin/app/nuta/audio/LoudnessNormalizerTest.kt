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

    /** Próbka [i] (przeplecione stereo) sinusa 440 Hz o zadanym RMS. */
    private fun sine(dbfs: Double, i: Int): Float =
        (amplitudeForRms(dbfs) * kotlin.math.sin(2.0 * kotlin.math.PI * 440.0 * (i / 2) / 48_000.0)).toFloat()

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

        assertTrue(n.gain < 0.9, "spodziewane tłumienie po głośnym utworze, gain=${n.gain}")

        n.reset()
        assertEquals(1.0, n.gain, "po reset() gain miał wrócić do 1.0")
    }

    @Test
    fun clearDelayOnSeekKeepsAttenuation() {
        // Seek nie może podnieść głośności w środku utworu — czyści tylko linię opóźniającą.
        val n = normalizer(LoudnessNormalization.NORMAL)
        repeat(48_000 * 2 * 2) { n.processSample(sine(-6.0, it)) }
        val before = n.gain
        n.clearDelay()
        assertEquals(before, n.gain)
    }

    @Test
    fun lookaheadAttenuatesLoudPartBeforeItIsHeard() {
        // Zgłoszone 16.10.2026: głośne wejście było słychać ~0.5–1 s, zanim zadziałało ściszenie.
        // Z lookaheadem już pierwsze próbki głośnego fragmentu wychodzą wyraźnie ściszone.
        val n = normalizer(LoudnessNormalization.NORMAL)
        val quietSamples = 48_000 * 2 * 2
        val loudAmplitude = amplitudeForRms(-4.0)
        val outputs = ArrayList<Float>()
        repeat(quietSamples) { outputs += n.processSample(sine(-30.0, it)) }
        repeat(48_000 * 2 * 2) { outputs += n.processSample(sine(-4.0, quietSamples + it)) }
        outputs.addAll(n.drain().toList())
        // drain() oddaje zawartość linii opóźniającej, więc wyjście jest dłuższe o samo opóźnienie
        // (600 ms stereo), a głośny fragment zaczyna się w outputs[quietSamples + delay].
        val delay = outputs.size - quietSamples - 48_000 * 2 * 2
        assertEquals(48_000 * 2 * 6 / 10, delay, "drain() miał oddać całą zawartość linii opóźniającej")
        val firstLoud = outputs.subList(quietSamples + delay, quietSamples + delay + 48_000 * 2 / 10)
        val peak = firstLoud.maxOf { abs(it) }
        assertTrue(peak < loudAmplitude * 0.5f, "początek głośnego fragmentu nieściszony: $peak vs $loudAmplitude")
    }

    @Test
    fun gainNeverRisesAfterLoudPartWithinTrack() {
        // Zapadka: po głośnym fragmencie cichszy NIE wraca do oryginalnej głośności — muzyka
        // ma nie falować góra-dół. Powrót do gain=1.0 tylko przez reset() (nowy utwór).
        val n = normalizer(LoudnessNormalization.NORMAL)
        repeat(48_000 * 2 * 3) { n.processSample(sine(-6.0, it)) }
        val afterLoud = n.gain
        repeat(48_000 * 2 * 3) { n.processSample(sine(-30.0, it)) }
        assertTrue(n.gain <= afterLoud, "po cichym fragmencie gain nie powinien wrócić w górę (${n.gain} > $afterLoud)")
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
