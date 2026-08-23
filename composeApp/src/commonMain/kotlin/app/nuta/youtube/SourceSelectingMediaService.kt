package app.nuta.youtube

import app.nuta.core.logging.NutaLogger
import app.nuta.core.models.Track
import app.nuta.settings.AudioSource
import app.nuta.settings.PlaybackSettingsStore

/**
 * Delegates to whichever concrete resolver matches the user's `AudioSource` setting, read
 * fresh on every call (so switching in Settings takes effect immediately, no restart needed).
 * Players (`Media3AudioPlayer`/`MpvAudioPlayer`) depend only on `YouTubeMediaService` — they
 * don't need to know SoundCloud exists.
 */
class SourceSelectingMediaService(
    private val settingsStore: PlaybackSettingsStore,
    private val youTube: YouTubeMediaService,
    private val soundCloud: YouTubeMediaService,
    private val logger: NutaLogger,
) : YouTubeMediaService {
    override suspend fun resolve(track: Track): YouTubeResolution = when (settingsStore.settings.value.audioSource) {
        AudioSource.YOUTUBE -> youTube.resolve(track)
        AudioSource.SOUNDCLOUD -> soundCloud.resolve(track)
        AudioSource.AUTO -> runCatching { youTube.resolve(track) }.getOrElse { error ->
            logger.warn("SourceSelector", "youtube_failed_falling_back", "YouTube nie zwrócił strumienia — próbuję SoundCloud", fields = mapOf(
                "track" to track.title,
                "reason" to (error.message ?: "unknown"),
            ))
            soundCloud.resolve(track)
        }
    }

    // Walidacja to ogólny GET z Range niezależny od tego, które źródło rozwiązało strumień —
    // implementacja obu konkretnych serwisów jest funkcjonalnie identyczna.
    override suspend fun validate(stream: AudioStreamSource) = youTube.validate(stream)
}
