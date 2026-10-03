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
 * AUTO i VISIONOS używają obecnie tego samego, jedynego działającego profilu. Fallback między
 * serwisami audio należy do [AudioSource.AUTO], nie do profili klienta YouTube.
 */
enum class YouTubeClientProfile { AUTO, VISIONOS }

/**
 * Skąd rozwiązywać strumień audio dla utworu. AUTO próbuje YouTube, a przy błędzie
 * (np. YouTube wymusi SABR na VISIONOS tak jak zrobił to wcześniej z WEB/TVHTML5/ANDROID_VR —
 * patrz docs/sabr-blocker/) automatycznie spada na SoundCloud dla tego
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

/** Pełne zestawy kolorystyczne UI (ciemne). FOREST = dotychczasowa zieleń Nuty. */
enum class AppTheme { FOREST, MIDNIGHT, VINYL, EMBER }

data class YouTubePlaybackSettings(
    val fontScale: Float = 1f,
    val quality: StreamQuality = StreamQuality.BEST,
    val codec: CodecPreference = CodecPreference.AAC,
    val bufferSize: BufferSize = BufferSize.STANDARD,
    val loudnessNormalization: LoudnessNormalization = LoudnessNormalization.OFF,
    val youtubeClientProfile: YouTubeClientProfile = YouTubeClientProfile.AUTO,
    val audioSource: AudioSource = AudioSource.YOUTUBE,
    val dataSource: DataSource = DataSource.LISTENBRAINZ,
    val listenBrainzUsername: String = "",
    /**
     * Sekret — nigdy nie loguj tego pola wprost. Domyślny toString() data class jest
     * nadpisany niżej właśnie po to, żeby token nie trafił do logów ani do debuggera.
     */
    val listenBrainzApiToken: String = "",
    /** Eksperymentalne: rozwiązuj strumień dla widocznych utworów zanim użytkownik kliknie play. */
    val prefetchEnabled: Boolean = false,
    /** Pasek playera jest zawsze widoczny; to pole trzyma tylko stan zwinięcia do wąskiego paska. */
    val playerCollapsed: Boolean = false,
    /** Limit cache'u zbuforowanych strumieni audio (MB). Zmiana działa dopiero po restarcie aplikacji. */
    val cacheSizeMb: Int = 150,
    val cassetteBackground: Boolean = true,
    val theme: AppTheme = AppTheme.FOREST,
) {
    override fun toString(): String = "YouTubePlaybackSettings(" +
        "fontScale=$fontScale, quality=$quality, codec=$codec, bufferSize=$bufferSize, " +
        "loudnessNormalization=$loudnessNormalization, youtubeClientProfile=$youtubeClientProfile, " +
        "audioSource=$audioSource, dataSource=$dataSource, listenBrainzUsername=$listenBrainzUsername, " +
        "listenBrainzApiToken=${if (listenBrainzApiToken.isBlank()) "" else "[REDACTED]"}, " +
        "prefetchEnabled=$prefetchEnabled, playerCollapsed=$playerCollapsed, cacheSizeMb=$cacheSizeMb, " +
        "cassetteBackground=$cassetteBackground, theme=$theme)"
}

interface PlaybackSettingsStore {
    val settings: StateFlow<YouTubePlaybackSettings>
    fun update(value: YouTubePlaybackSettings)
    fun update(transform: (YouTubePlaybackSettings) -> YouTubePlaybackSettings) {
        update(transform(settings.value))
    }
}

/** Trwały magazyn sekretów; implementacje platformowe szyfrują dane poza plikiem ustawień. */
interface CredentialStore {
    fun load(key: String): String?
    fun save(key: String, value: String)
    fun clear(key: String)
}

class InMemoryPlaybackSettingsStore(
    initial: YouTubePlaybackSettings = YouTubePlaybackSettings(),
) : PlaybackSettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<YouTubePlaybackSettings> = state.asStateFlow()
    override fun update(value: YouTubePlaybackSettings) { state.value = value }
}
