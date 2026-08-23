package app.nuta.youtube

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
) : YouTubeMediaService {
    private fun active(): YouTubeMediaService = when (settingsStore.settings.value.audioSource) {
        AudioSource.SOUNDCLOUD -> soundCloud
        AudioSource.YOUTUBE -> youTube
    }

    override suspend fun resolve(track: Track): YouTubeResolution = active().resolve(track)
    override suspend fun validate(stream: AudioStreamSource) = active().validate(stream)
}
