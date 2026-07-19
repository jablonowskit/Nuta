package app.nuta.android

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
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
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                PlaybackQueueBridge.buffering.value = playbackState == Player.STATE_BUFFERING
            }
        })
        val seekBack = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
            .setDisplayName("Cofnij 10 sekund")
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .build()
        val seekForward = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
            .setDisplayName("Przewiń 10 sekund")
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .build()
        mediaSession = MediaSession.Builder(this, QueueAwarePlayer(player)).build()
        mediaSession?.setMediaButtonPreferences(listOf(seekBack, seekForward))
    }

    private class QueueAwarePlayer(player: Player) : ForwardingPlayer(player) {
        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
            .addAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
            .build()

        override fun isCommandAvailable(command: Int): Boolean = getAvailableCommands().contains(command)
        override fun hasNextMediaItem(): Boolean = PlaybackQueueBridge.hasNext.value
        override fun hasPreviousMediaItem(): Boolean = PlaybackQueueBridge.hasPrevious.value
        // maskujemy BUFFERING jako READY: system zamienia play/pause na wskaźnik ładowania, a chcemy stałe przyciski
        override fun getPlaybackState(): Int = when (val state = super.getPlaybackState()) {
            Player.STATE_BUFFERING -> Player.STATE_READY
            else -> state
        }
        override fun isPlaying(): Boolean = playWhenReady &&
            playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
            super.getPlaybackState().let { it == Player.STATE_READY || it == Player.STATE_BUFFERING }
        override fun seekToNext() { PlaybackQueueBridge.onNext?.invoke() }
        override fun seekToNextMediaItem() { PlaybackQueueBridge.onNext?.invoke() }
        override fun seekToPrevious() { PlaybackQueueBridge.onPrevious?.invoke() }
        override fun seekToPreviousMediaItem() { PlaybackQueueBridge.onPrevious?.invoke() }
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
