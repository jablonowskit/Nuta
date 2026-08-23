package app.nuta.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StreamQuality { AUTO, DATA_SAVER, STANDARD, BEST }
enum class CodecPreference { AUTO, AAC, OPUS }
enum class BufferSize { SMALL, STANDARD, LARGE }
enum class LoudnessNormalization { OFF, GENTLE, NORMAL }
/**
 * Który profil klienta InnerTube resolver ma próbować przy rozwiązywaniu strumienia YouTube.
 * AUTO = VISIONOS jako pierwszy, potem ANDROID_VR jako fallback (jedyne dwa profile, które wg
 * testów z 22.08.2026 dają cokolwiek użyteczne — WEB/ANDROID/IOS/TVHTML5 usunięte jako bezsensowne,
 * patrz openspec/changes/2026-08-sabr-blocker/). Wybór konkretnego klienta próbuje TYLKO tego
 * jednego, bez fallbacku — przydatne do ręcznego diagnozowania, który profil akurat działa.
 */
enum class YouTubeClientProfile { AUTO, VISIONOS, ANDROID_VR }

/**
 * Skąd rozwiązywać strumień audio dla utworu. AUTO próbuje YouTube, a przy błędzie
 * (np. YouTube wymusi SABR na VISIONOS tak jak zrobił to wcześniej z WEB/TVHTML5/ANDROID_VR —
 * patrz openspec/changes/2026-08-sabr-blocker/) automatycznie spada na SoundCloud dla tego
 * samego utworu. YOUTUBE/SOUNDCLOUD to jawny, wymuszony wybór jednego źródła bez fallbacku —
 * przydatne do diagnozowania, które źródło akurat działa.
 */
enum class AudioSource { AUTO, YOUTUBE, SOUNDCLOUD }

/**
 * Skąd brać wyszukiwanie, playlisty, ulubione i rekomendacje. LISTENBRAINZ całkowicie
 * zastępuje Spotify (search przez MusicBrainz, reszta przez ListenBrainz) — żadnego
 * mergowania, tak jakby drugi serwis nie istniał. Niezależne od AudioSource (audio zawsze
 * leci z YouTube/SoundCloud).
 */
enum class DataSource { SPOTIFY, LISTENBRAINZ }

data class YouTubePlaybackSettings(
    val fontScale: Float = 1f,
    val quality: StreamQuality = StreamQuality.BEST,
    val codec: CodecPreference = CodecPreference.AAC,
    val bufferSize: BufferSize = BufferSize.STANDARD,
    val loudnessNormalization: LoudnessNormalization = LoudnessNormalization.OFF,
    val youtubeClientProfile: YouTubeClientProfile = YouTubeClientProfile.AUTO,
    val audioSource: AudioSource = AudioSource.YOUTUBE,
    val dataSource: DataSource = DataSource.SPOTIFY,
    val listenBrainzUsername: String = "",
    val listenBrainzApiToken: String = "",
    /** Eksperymentalne: rozwiązuj strumień dla widocznych utworów zanim użytkownik kliknie play. */
    val prefetchEnabled: Boolean = false,
    /** Pasek playera jest zawsze widoczny; to pole trzyma tylko stan zwinięcia do wąskiego paska. */
    val playerCollapsed: Boolean = false,
    /** Limit cache'u zbuforowanych strumieni audio (MB). Zmiana działa dopiero po restarcie aplikacji. */
    val cacheSizeMb: Int = 150,
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
