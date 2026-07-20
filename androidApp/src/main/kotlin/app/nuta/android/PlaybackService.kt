package app.nuta.android

import android.os.Bundle
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.nuta.settings.BufferSize
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var streamCache: SimpleCache? = null

    override fun onCreate() {
        super.onCreate()
        val settingsStore = AndroidPlaybackSettingsStore(getSharedPreferences("playback-settings", MODE_PRIVATE))
        val upstreamFactory = DefaultHttpDataSource.Factory().setUserAgent(USER_AGENT).setAllowCrossProtocolRedirects(true)
        val cache = SimpleCache(File(cacheDir, "stream-cache"), LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES), StandaloneDatabaseProvider(this))
        streamCache = cache
        val cacheFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        PlaybackQueueBridge.streamCacheFactory = cacheFactory
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .setLoadControl(loadControl(settingsStore.settings.value.bufferSize))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                PlaybackQueueBridge.buffering.value = playbackState == Player.STATE_BUFFERING
            }
        })
        // ±10s jako CUSTOM SessionCommand: system Android 13+ renderuje w powiadomieniu tylko
        // standardowe prev/play/next + custom actions — player command SEEK_BACK/FORWARD ląduje
        // jako REWIND/FAST_FORWARD, których systemowe kontrolki nie pokazują wcale
        // SLOT_OVERFLOW (nie SECONDARY): tylko overflow eksportuje się jako custom actions
        // do sesji platformowej, z której czyta panel multimediów Samsunga
        val seekBackButton = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
            .setDisplayName("Cofnij 10 sekund")
            .setSessionCommand(SessionCommand(COMMAND_SEEK_BACK_10, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
        val seekForwardButton = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
            .setDisplayName("Przewiń 10 sekund")
            .setSessionCommand(SessionCommand(COMMAND_SEEK_FORWARD_10, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
        mediaSession = MediaSession.Builder(this, QueueAwarePlayer(player))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(
                            MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                                .add(SessionCommand(COMMAND_SEEK_BACK_10, Bundle.EMPTY))
                                .add(SessionCommand(COMMAND_SEEK_FORWARD_10, Bundle.EMPTY))
                                .build(),
                        )
                        .build()

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        COMMAND_SEEK_BACK_10 -> session.player.seekBack()
                        COMMAND_SEEK_FORWARD_10 -> session.player.seekForward()
                        else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()
        mediaSession?.setMediaButtonPreferences(listOf(seekBackButton, seekForwardButton))
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
        PlaybackQueueBridge.streamCacheFactory = null
        streamCache?.release()
        streamCache = null
        super.onDestroy()
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/128.0 Mobile Safari/537.36"
        private const val CACHE_SIZE_BYTES = 150L * 1024 * 1024
        private const val COMMAND_SEEK_BACK_10 = "app.nuta.SEEK_BACK_10"
        private const val COMMAND_SEEK_FORWARD_10 = "app.nuta.SEEK_FORWARD_10"

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
