package app.nuta.youtube

import app.nuta.settings.CodecPreference
import app.nuta.settings.StreamQuality

/**
 * Wybór strumienia audio wspólny dla Androida i desktopu — wcześniej każda platforma
 * miała własną kopię i rozjechały się (desktop w ogóle ignorował ustawienia i twardo
 * preferował Opus, wbrew domyślnemu AAC).
 */
object AudioFormatSelector {

    /**
     * @param unmeteredNetwork czy połączenie jest nielimitowane (Wi-Fi). Sterowanie tylko dla
     *   [StreamQuality.AUTO]; `null` oznacza brak informacji o taryfie (desktop) i jest
     *   traktowane jak połączenie nielimitowane.
     * @param systemDataSaver czy użytkownik włączył w systemie oszczędzanie danych. Liczy się
     *   wyłącznie na połączeniu limitowanym — na Wi-Fi system i tak go nie stosuje.
     */
    fun select(
        formats: List<AudioStreamSource>,
        quality: StreamQuality,
        codec: CodecPreference,
        unmeteredNetwork: Boolean? = null,
        systemDataSaver: Boolean = false,
    ): AudioStreamSource? {
        if (formats.isEmpty()) return null
        val preferred = formats.filter { matchesCodec(it, codec) }.ifEmpty { formats }
        val effectiveQuality = if (quality == StreamQuality.AUTO) autoQuality(unmeteredNetwork, systemDataSaver) else quality
        return when (effectiveQuality) {
            StreamQuality.DATA_SAVER -> preferred.pickNearest(64_000, codec)
            StreamQuality.STANDARD -> preferred.pickNearest(128_000, codec)
            StreamQuality.BEST, StreamQuality.AUTO -> preferred.pickBest(codec)
        }
    }

    /**
     * AUTO wcześniej celowało dokładnie w 128 kbps, więc Opus 150 zawsze przegrywał z AAC 128
     * (odległość 0) — był odrzucany za to, że jest lepszy.
     */
    private fun autoQuality(unmeteredNetwork: Boolean?, systemDataSaver: Boolean): StreamQuality = when {
        // brak informacji o taryfie (desktop) traktujemy jak Wi-Fi
        unmeteredNetwork != false -> StreamQuality.BEST
        systemDataSaver -> StreamQuality.DATA_SAVER
        else -> StreamQuality.STANDARD
    }

    private fun matchesCodec(stream: AudioStreamSource, codec: CodecPreference): Boolean = when (codec) {
        CodecPreference.AUTO -> true
        CodecPreference.AAC -> stream.isAac
        CodecPreference.OPUS -> stream.isOpus
    }

    /**
     * Przy równym bitrate wygrywa Opus, bo przy tej samej przepływności brzmi lepiej niż AAC.
     * Rozstrzyganie ma sens tylko wtedy, gdy użytkownik nie wskazał kodeka samodzielnie.
     */
    private fun List<AudioStreamSource>.pickBest(codec: CodecPreference): AudioStreamSource? =
        maxWithOrNull(compareBy({ it.bitrate }, { it.codecBonus(codec) }))

    private fun List<AudioStreamSource>.pickNearest(targetBitrate: Int, codec: CodecPreference): AudioStreamSource? =
        minWithOrNull(compareBy({ abs(it.bitrate - targetBitrate) }, { -it.codecBonus(codec) }))

    private fun AudioStreamSource.codecBonus(codec: CodecPreference): Int =
        if (codec == CodecPreference.AUTO && isOpus) 1 else 0

    private val AudioStreamSource.isAac: Boolean
        get() = codec.contains("mp4a", ignoreCase = true) || codec.contains("aac", ignoreCase = true)

    private val AudioStreamSource.isOpus: Boolean
        get() = codec.contains("opus", ignoreCase = true)

    private fun abs(value: Int): Int = if (value < 0) -value else value
}
