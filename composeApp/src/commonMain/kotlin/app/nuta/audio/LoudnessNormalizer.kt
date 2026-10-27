package app.nuta.audio

import app.nuta.settings.LoudnessNormalization
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Normalizacja głośności działająca na sygnale: mierzy bieżący poziom RMS i **ścisza fragmenty
 * głośniejsze niż cel** — w przeciwieństwie do `android.media.audiofx.LoudnessEnhancer`, który
 * potrafi tylko wzmacniać (i którego część urządzeń, m.in. Galaxy A55, nie inicjalizuje wcale —
 * `Error: -3`).
 *
 * Świadomie NIE podnosi cichych fragmentów (`maxBoostDb = 0`, patrz niżej) — zgłoszone na słuch
 * 19.09.2026: wcześniejsze wzmacnianie ciszy w połączeniu z tłumieniem szczytów dawało słyszalne,
 * powtarzające się "pompowanie" w rytm utworu (różnica między podniesioną ciszą a ściszonym
 * szczytem wypadała mocniej niż faktyczna dynamika oryginału). Sam-tłumienie jest bardziej
 * przewidywalne: ciche partie zostają w spokoju, korygowane są tylko wyraźne szczyty.
 *
 * Czysta logika, bez zależności od Androida ani mpv, żeby dała się przetestować jednostkowo;
 * platformowe opakowanie (`AudioProcessor` w Media3) tylko podaje jej próbki.
 *
 * Algorytm (jednoprzebiegowy, bo strumień jest odtwarzany na żywo — nie znamy go z góry):
 * 1. RMS liczony w oknie ~200 ms.
 * 2. Z RMS wynika wzmocnienie potrzebne do trafienia w cel, ograniczone do [maxAttenuation,
 *    maxBoost] (maxBoost=0, więc realnie tylko do [maxAttenuation, 1.0]).
 * 3. Wzmocnienie w obrębie utworu tylko maleje (zapadka) i schodzi płynnie (attack) — nigdy
 *    nie wraca w górę, żeby głośność nie falowała. Wraca do 1.0 dopiero [reset] na nowy utwór.
 * 4. Limiter twardo przycina próbki przekraczające [Ceiling] — ale tylko przy wzmocnieniu > 1.
 *    Przy samym tłumieniu nic nie może wyjść poza oryginał, a obcinanie go do -1.5 dBFS
 *    spłaszczało szczyty dynamicznych nagrań (klasyka, jazz, stare mastery) — zniekształcenie
 *    bez żadnej korzyści. Dziś maxBoostDb=0, więc limiter jest bezpiecznikiem na przyszłość.
 *
 * Świadomie NIE jest to pełne EBU R128 (brak K-weightingu i gatingu): różnica jest słyszalna
 * głównie w materiale o dużej dynamice, a koszt to kilkaset linii DSP. Desktop używa
 * ffmpegowego `loudnorm` przez mpv, więc parytet między platformami jest funkcjonalny
 * („obie wyrównują głośność"), nie identyczny próbka w próbkę.
 */
class LoudnessNormalizer(
    private val sampleRateHz: Int,
    /** Liczba kanałów — okno pomiaru liczymy w próbkach przeplecionych, więc skaluje jego długość. */
    private val channelCount: Int,
    mode: LoudnessNormalization,
) {
    /** Poziom docelowy w dBFS. Odpowiada celom `loudnorm` na desktopie (I=-16 / I=-20 LUFS). */
    private val targetDbfs: Double = when (mode) {
        LoudnessNormalization.OFF -> 0.0
        LoudnessNormalization.GENTLE -> -20.0
        LoudnessNormalization.NORMAL -> -16.0
    }

    /**
     * Zgłoszone na słuch 19.09.2026: wzmacnianie cichych fragmentów (dawne +6/+12 dB) w
     * połączeniu z tłumieniem głośnych dawało słyszalne "pompowanie" w rytm utworu — różnica
     * między podniesioną ciszą a ściszonym szczytem wypadała mocniej niż faktyczna dynamika
     * oryginału. maxBoostDb=0 usuwa wzmacnianie całkowicie: normalizator już tylko tłumi
     * głośne fragmenty (gainFor zwraca co najwyżej 1.0, nigdy więcej), nie dotyka cichych.
     * GENTLE nadal koryguje łagodniej niż NORMAL — mniejszy dozwolony zakres tłumienia.
     */
    private val maxBoostDb: Double = 0.0
    private val maxAttenuationDb: Double = if (mode == LoudnessNormalization.GENTLE) -6.0 else -12.0

    private val windowSamples: Int = (sampleRateHz * WindowMs / 1000).coerceAtLeast(1) * channelCount
    private var sumOfSquares = 0.0
    private var samplesInWindow = 0
    /** Wzmocnienie liniowe stosowane teraz; 1.0 = brak zmiany. */
    private var currentGain = 1.0
    private var targetGain = 1.0

    val active: Boolean get() = targetDbfs != 0.0

    /** Bieżące wzmocnienie liniowe (do testów). */
    internal val gain: Double get() = currentGain

    /**
     * Zeruje stan pomiaru i wzmocnienia. Wołane tylko przy zmianie utworu (NIE przy seeku —
     * wtedy głośność skoczyłaby w górę w środku utworu): bez tego tłumienie wyliczone dla
     * poprzedniego materiału (nawet -12 dB) obowiązywałoby do końca kolejnego utworu.
     */
    fun reset() {
        clearDelay()
        currentGain = 1.0
        targetGain = 1.0
    }

    /**
     * Seek: zaczyna pomiar od nowa, ale zachowuje wzmocnienie — głośność nie skacze w górę
     * w środku utworu.
     */
    fun clearDelay() {
        sumOfSquares = 0.0
        samplesInWindow = 0
    }

    /**
     * Przetwarza jedną próbkę znormalizowaną do [-1, 1]. Bez opóźnienia: lookahead (600 ms,
     * 16.10.2026) usunięty 24.10.2026 — dawał 600 ms ciszy po każdym seeku i starcie utworu
     * oraz przesuwał dźwięk względem pozycji Media3. Szybkie łapanie głośnego wejścia daje
     * teraz krótsze okno pomiaru ([WindowMs]).
     */
    fun processSample(sample: Float): Float {
        if (!active) return sample
        accumulate(sample)
        currentGain = approach(currentGain, targetGain)
        val out = sample * currentGain.toFloat()
        return if (currentGain > 1.0) limit(out) else out
    }

    private fun accumulate(sample: Float) {
        sumOfSquares += sample.toDouble() * sample.toDouble()
        samplesInWindow++
        if (samplesInWindow < windowSamples) return
        val rms = sqrt(sumOfSquares / samplesInWindow)
        sumOfSquares = 0.0
        samplesInWindow = 0
        // Zapadka: w obrębie utworu wzmocnienie może tylko spadać. Raz ściszony utwór zostaje
        // ściszony do końca (do [reset] przy zmianie utworu) — cichsza zwrotka po głośnym
        // refrenie nie wraca do oryginalnej głośności, więc muzyka nie "faluje" góra-dół.
        targetGain = minOf(targetGain, gainFor(rms))
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
     * Płynne dojście w dół do docelowego wzmocnienia. Drogi w górę nie ma — [targetGain]
     * tylko maleje (patrz [accumulate]). Zbyt krótki attack (dawne 50 ms) było słychać jako
     * "szarpnięcie" w dół, patrz [AttackMs].
     */
    private fun approach(from: Double, to: Double): Double {
        if (to >= from) return from
        // processSample idzie po próbkach przeplecionych — przy stereo dwa wywołania na ramkę,
        // więc bez channelCount attack był dwa razy krótszy (100 ms działało jak 50 ms)
        val stepMs = 1000.0 / (sampleRateHz * channelCount)
        val step = (stepMs / AttackMs).coerceIn(0.0, 1.0)
        return from + (to - from) * step
    }

    /** Twarde ograniczenie do sufitu — po wzmocnieniu szczyty mogłyby wyjść poza zakres. */
    private fun limit(sample: Float): Float =
        if (abs(sample) <= Ceiling) sample else if (sample > 0) Ceiling else -Ceiling

    internal companion object {
        /**
         * Okno pomiaru 200 ms (krócej niż 400 ms „momentary loudness" z EBU R128): bez lookaheadu
         * od tego zależy, jak długo słychać głośne wejście przed ściszeniem.
         */
        const val WindowMs = 200
        /** Poniżej tego poziomu nie korygujemy (przerwy, wybrzmienia). */
        const val SilenceFloorDbfs = -50.0
        /** -1.5 dBFS, ten sam sufit co `TP=-1.5` w desktopowym loudnorm. */
        const val Ceiling = 0.84f
        /**
         * Historia: zgłoszone na słuch 19.09.2026: przy poprzednim 50 ms wzrost głośności (np. wejście
         * refrenu) dawał słyszalne, powtarzające się "szarpnięcie" w dół przez cały utwór —
         * klasyczny efekt pompowania (pumping) znany z agresywnej kompresji. Policzone: 50 ms
         * dawało 90% korekty w ~115 ms dla typowego skoku zwrotka→refren (16 dB) — wystarczająco
         * szybko, żeby ucho usłyszało moment "łapania" głośności, zamiast płynnego dostosowania.
         * 250 ms rozciąga to do ~576 ms, wciąż mieszcząc się w 2-sekundowym oknie stabilizacji,
         * które zakładają testy w LoudnessNormalizerTest (rmsAfterProcessing liczy RMS z drugiej
         * połowy 4-sekundowego sygnału).
         */
        const val AttackMs = 100.0
    }
}
