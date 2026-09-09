package app.nuta.android

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.C
import app.nuta.audio.LoudnessNormalizer
import app.nuta.settings.LoudnessNormalization
import app.nuta.settings.PlaybackSettingsStore
import java.nio.ByteBuffer

/**
 * Normalizacja głośności wpleciona w łańcuch audio ExoPlayera — softwarowo, na próbkach PCM.
 *
 * Zastępuje `android.media.audiofx.LoudnessEnhancer`, który miał dwie wady:
 * potrafił tylko **wzmacniać** (nigdy nie ściszał głośnych utworów, więc nie wyrównywał
 * głośności między nimi), a na części urządzeń — w tym Galaxy A55 użytkownika — nie
 * inicjalizował się wcale (`Cannot initialize effect engine ... Error: -3`), bo zależał od
 * sprzętowego efektu. Ten procesor działa na każdym urządzeniu, bo liczy wszystko sam.
 *
 * Cała logika DSP siedzi we wspólnym [LoudnessNormalizer] (przetestowanym jednostkowo);
 * ta klasa tylko rozpakowuje bufor PCM 16-bit na próbki i pakuje wynik z powrotem.
 */
class LoudnessAudioProcessor(
    private val settingsStore: PlaybackSettingsStore,
) : BaseAudioProcessor() {
    private var normalizer: LoudnessNormalizer? = null
    private var activeMode: LoudnessNormalization? = null

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Umiemy przetwarzać tylko 16-bitowe PCM. Kontrakt BaseAudioProcessor każe w takim
        // wypadku rzucić UnhandledAudioFormatException (NOT_SET oznaczałby "procesor nieaktywny",
        // a nie "nie obsługuję tego formatu") — Media3 wybierze wtedy inną ścieżkę.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        rebuildNormalizer(inputAudioFormat)
        return inputAudioFormat
    }

    private fun rebuildNormalizer(format: AudioProcessor.AudioFormat) {
        val mode = settingsStore.settings.value.loudnessNormalization
        activeMode = mode
        normalizer = LoudnessNormalizer(format.sampleRate, format.channelCount, mode)
    }

    /**
     * Celowo NIE uwzględniamy tu aktualnego trybu. `DefaultAudioSink` buduje potok procesorów
     * raz, przy `configure()`, i nie przebudowuje go przy zmianie ustawień — gdyby `isActive()`
     * zwróciło false dla OFF, procesor zostałby wypięty z potoku na cały utwór i późniejsze
     * włączenie normalizacji nie zadziałałoby do zmiany utworu. Dlatego jesteśmy zawsze aktywni
     * (o ile format jest obsłużony), a tryb OFF obsługujemy przepisując próbki 1:1 w [queueInput].
     */
    override fun isActive(): Boolean = super.isActive()

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        // Zmiana ustawienia w trakcie odtwarzania: budujemy normalizer od nowa, żeby od razu
        // liczył według nowego celu (bez tego trzeba by restartować odtwarzanie).
        val mode = settingsStore.settings.value.loudnessNormalization
        if (mode != activeMode) rebuildNormalizer(inputAudioFormat)
        val current = normalizer

        val size = inputBuffer.remaining()
        val output = replaceOutputBuffer(size)
        if (current == null || !current.active) {
            // Tryb OFF: przepisujemy bajty bez zmian (procesor zostaje w potoku — patrz isActive).
            output.put(inputBuffer)
            output.flip()
            return
        }
        // Kolejności bajtów NIE ustawiamy jawnie na żadnym z buforów: replaceOutputBuffer daje
        // bufor w nativeOrder(), a wejściowy przychodzi w tej samej kolejności — dokładnie tak
        // samo robi wbudowany SonicAudioProcessor (asShortBuffer bez własnego order()).
        val input = inputBuffer.asShortBuffer()
        val out = output.asShortBuffer()
        while (input.hasRemaining()) {
            val sample = input.get().toFloat() / Short.MAX_VALUE
            val processed = current.processSample(sample)
            out.put((processed * Short.MAX_VALUE).toInt().coerceIn(MinPcm, MaxPcm).toShort())
        }
        // Przesuwamy pozycje: widoki ShortBuffer nie ruszają pozycji buforów bazowych.
        inputBuffer.position(inputBuffer.position() + size)
        output.position(out.position() * 2)
        output.flip()
    }

    override fun onReset() {
        normalizer = null
        activeMode = null
    }

    private companion object {
        const val MinPcm = -32768
        const val MaxPcm = 32767
    }
}
