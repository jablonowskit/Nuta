package app.nuta.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StreamQuality { AUTO, DATA_SAVER, STANDARD, BEST }
enum class CodecPreference { AUTO, AAC, OPUS }
enum class BufferSize { SMALL, STANDARD, LARGE }
enum class LoudnessNormalization { OFF, GENTLE, NORMAL }

data class YouTubePlaybackSettings(
    val quality: StreamQuality = StreamQuality.BEST,
    val codec: CodecPreference = CodecPreference.AAC,
    val bufferSize: BufferSize = BufferSize.STANDARD,
    val loudnessNormalization: LoudnessNormalization = LoudnessNormalization.OFF,
)

interface PlaybackSettingsStore {
    val settings: StateFlow<YouTubePlaybackSettings>
    fun update(value: YouTubePlaybackSettings)
}

class InMemoryPlaybackSettingsStore(
    initial: YouTubePlaybackSettings = YouTubePlaybackSettings(),
) : PlaybackSettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<YouTubePlaybackSettings> = state.asStateFlow()
    override fun update(value: YouTubePlaybackSettings) { state.value = value }
}
