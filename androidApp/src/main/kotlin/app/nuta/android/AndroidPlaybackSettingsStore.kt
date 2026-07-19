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
            .putFloat("fontScale", value.fontScale)
            .putString("youtubeQuality", value.quality.name)
            .putString("youtubeCodec", value.codec.name)
            .putString("youtubeBuffer", value.bufferSize.name)
            .putString("loudnessNormalization", value.loudnessNormalization.name)
            .putInt("homeRecommendations", value.homeRecommendations)
            .apply()
        state.value = value
    }

    private fun read() = YouTubePlaybackSettings(
        fontScale = preferences.getFloat("fontScale", 1f).coerceIn(0.5f, 1f),
        quality = enumValue(preferences.getString("youtubeQuality", null), StreamQuality.BEST),
        codec = enumValue(preferences.getString("youtubeCodec", null), CodecPreference.AAC),
        bufferSize = enumValue(preferences.getString("youtubeBuffer", null), BufferSize.STANDARD),
        loudnessNormalization = enumValue(preferences.getString("loudnessNormalization", null), LoudnessNormalization.OFF),
        homeRecommendations = preferences.getInt("homeRecommendations", 6).coerceIn(3, 50),
    )

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
}
