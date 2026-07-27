package app.nuta.youtube

import app.nuta.core.security.SecretValue
import app.nuta.settings.CodecPreference
import app.nuta.settings.StreamQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioFormatSelectorTest {
    private fun stream(codec: String, bitrate: Int) = AudioStreamSource(
        url = SecretValue.of("https://example.test/$codec/$bitrate"),
        mimeType = "audio/webm; codecs=\"$codec\"",
        container = "webm",
        codec = codec,
        bitrate = bitrate,
        contentLength = null,
        expiresAtMs = null,
    )

    private val aac128 = stream("mp4a.40.2", 128_000)
    private val opus150 = stream("opus", 150_000)
    private val opus64 = stream("opus", 64_000)
    private val formats = listOf(aac128, opus150, opus64)

    @Test
    fun autoQualityPrefersBetterStreamOverExactly128k() {
        // Regresja: AUTO celowało dokładnie w 128 kbps, więc AAC 128 (odległość 0) zawsze
        // wygrywał z lepszym Opusem 150 — mimo że użytkownik nie wskazał żadnego kodeka.
        val selected = AudioFormatSelector.select(formats, StreamQuality.AUTO, CodecPreference.AUTO)
        assertEquals(opus150, selected)
    }

    @Test
    fun autoQualitySavesDataOnMeteredNetwork() {
        val selected = AudioFormatSelector.select(formats, StreamQuality.AUTO, CodecPreference.AUTO, unmeteredNetwork = false)
        assertEquals(aac128, selected)
    }

    @Test
    fun autoQualityTakesBestOnUnmeteredNetwork() {
        val selected = AudioFormatSelector.select(formats, StreamQuality.AUTO, CodecPreference.AUTO, unmeteredNetwork = true)
        assertEquals(opus150, selected)
    }

    @Test
    fun autoQualityDropsToDataSaverOnMeteredNetworkWithSystemDataSaverOn() {
        val selected = AudioFormatSelector.select(
            formats, StreamQuality.AUTO, CodecPreference.AUTO,
            unmeteredNetwork = false, systemDataSaver = true,
        )
        assertEquals(opus64, selected)
    }

    @Test
    fun systemDataSaverIsIgnoredOnUnmeteredNetwork() {
        // system nie stosuje oszczędzania danych na Wi-Fi, więc my też nie obniżamy jakości
        val selected = AudioFormatSelector.select(
            formats, StreamQuality.AUTO, CodecPreference.AUTO,
            unmeteredNetwork = true, systemDataSaver = true,
        )
        assertEquals(opus150, selected)
    }

    @Test
    fun explicitCodecStillWinsOverHigherBitrate() {
        val selected = AudioFormatSelector.select(formats, StreamQuality.BEST, CodecPreference.AAC)
        assertEquals(aac128, selected)
    }

    @Test
    fun opusBreaksTiesOnlyWhenCodecIsAuto() {
        val aac150 = stream("mp4a.40.2", 150_000)
        val tie = listOf(aac150, opus150)
        assertEquals(opus150, AudioFormatSelector.select(tie, StreamQuality.BEST, CodecPreference.AUTO))
        assertEquals(aac150, AudioFormatSelector.select(tie, StreamQuality.BEST, CodecPreference.AAC))
    }

    @Test
    fun codecFilterFallsBackWhenNothingMatches() {
        val onlyOpus = listOf(opus150, opus64)
        assertEquals(opus150, AudioFormatSelector.select(onlyOpus, StreamQuality.BEST, CodecPreference.AAC))
    }

    @Test
    fun dataSaverPicksLowestUsableBitrate() {
        assertEquals(opus64, AudioFormatSelector.select(formats, StreamQuality.DATA_SAVER, CodecPreference.AUTO))
    }

    @Test
    fun emptyFormatListYieldsNull() {
        assertNull(AudioFormatSelector.select(emptyList(), StreamQuality.AUTO, CodecPreference.AUTO))
    }
}
