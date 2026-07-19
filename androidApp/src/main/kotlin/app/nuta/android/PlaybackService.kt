package app.nuta.android

import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.nuta.settings.BufferSize

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val settingsStore = AndroidPlaybackSettingsStore(getSharedPreferences("playback-settings", MODE_PRIVATE))
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultHttpDataSource.Factory().setUserAgent(USER_AGENT).setAllowCrossProtocolRedirects(true)))
            .setLoadControl(loadControl(settingsStore.settings.value.bufferSize))
            .build()
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/128.0 Mobile Safari/537.36"

        private fun loadControl(size: BufferSize): DefaultLoadControl {
            val values = when (size) {
                BufferSize.SMALL -> intArrayOf(5_000, 15_000, 500, 1_000)
                BufferSize.STANDARD -> intArrayOf(15_000, 50_000, 1_500, 3_000)
                BufferSize.LARGE -> intArrayOf(30_000, 120_000, 3_000, 5_000)
            }
            return DefaultLoadControl.Builder().setBufferDurationsMs(values[0], values[1], values[2], values[3]).build()
        }
    }
}
