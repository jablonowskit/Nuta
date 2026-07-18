package app.nuta.android

import android.content.SharedPreferences
import app.nuta.settings.BufferSize
import app.nuta.settings.CodecPreference
import app.nuta.settings.PlaybackSettingsStore
import app.nuta.settings.StreamQuality
import app.nuta.settings.YouTubePlaybackSettings
import app.nuta.settings.LoudnessNormalization
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidPlaybackSettingsStore(private val preferences: SharedPreferences) : PlaybackSettingsStore {
    private val state = MutableStateFlow(read())
    override val settings: StateFlow<YouTubePlaybackSettings> = state.asStateFlow()

    override fun update(value: YouTubePlaybackSettings) {
        preferences.edit()
            .putString("youtubeQuality", value.quality.name)
            .putString("youtubeCodec", value.codec.name)
            .putString("youtubeBuffer", value.bufferSize.name)
            .putString("loudnessNormalization", value.loudnessNormalization.name)
            .apply()
        state.value = value
    }

    private fun read() = YouTubePlaybackSettings(
        quality = enumValue(preferences.getString("youtubeQuality", null), StreamQuality.BEST),
        codec = enumValue(preferences.getString("youtubeCodec", null), CodecPreference.AAC),
        bufferSize = enumValue(preferences.getString("youtubeBuffer", null), BufferSize.STANDARD),
        loudnessNormalization = enumValue(preferences.getString("loudnessNormalization", null), LoudnessNormalization.OFF),
    )

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
}
