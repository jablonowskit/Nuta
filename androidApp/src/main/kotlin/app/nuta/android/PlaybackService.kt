package app.nuta.android

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
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
        AppServices.start(this)
        val settingsStore = AndroidPlaybackSettingsStore(getSharedPreferences("playback-settings", MODE_PRIVATE))
        val upstreamFactory = ClientAwareDataSourceFactory(DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true), AppServices.logger)
        val cacheSizeBytes = settingsStore.settings.value.cacheSizeMb.toLong() * 1024 * 1024
        val cache = SimpleCache(File(cacheDir, "stream-cache"), LeastRecentlyUsedCacheEvictor(cacheSizeBytes), StandaloneDatabaseProvider(this))
        streamCache = cache
        val cacheFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        PlaybackQueueBridge.streamCacheFactory = cacheFactory
        PlaybackQueueBridge.streamCache = cache
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
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaSession.Builder(this, QueueAwarePlayer(player))
            .setSessionActivity(openAppIntent)
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

    /**
     * Google CDN odrzuca (HTTP 403) pobranie bajtów audio, jeśli User-Agent żądania danych nie
     * zgadza się z UA klienta, dla którego URL został podpisany — a różne profile YouTube
     * (VISIONOS/ANDROID_VR, wybierane przez ustawienie w Settings albo fallback AUTO) dają URL-e
     * podpisane dla różnych klientów w tej samej sesji odtwarzania. Zamiast pamiętać, który profil
     * aktualnie "wygrał" (osobny stan do synchronizowania z resolverem), UA wybieramy na podstawie
     * parametru "c=" wpisanego w sam URL strumienia przez Google — widoczny wprost w każdym
     * podpisanym linku (np. "&c=ANDROID_VR&" albo "&c=VISIONOS&"), więc zawsze zgodny z tym, co
     * faktycznie podpisało dany URL, niezależnie od ustawień.
     *
     * Druga, ważniejsza sprawa: lokalny węzeł CDN (Google Global Cache) u tego ISP akceptuje
     * OGRANICZONE zakresy bajtów ("Range: bytes=X-Y"), ale odrzuca (HTTP 403) KONTYNUACJĘ otwartym
     * zakresem ("Range: bytes=X-", bez górnej granicy) — potwierdzone curlem: pierwszy ograniczony
     * zakres działa, kolejny otwarty od tego miejsca pada natychmiast. ExoPlayer po wyczerpaniu
     * pierwszego bufora domyślnie właśnie tak kontynuuje odtwarzanie, stąd stały ~3.4s cutoff
     * niezależny od profilu klienta, User-Agenta, telefonu czy sieci. Fix: zawsze dopisujemy
     * górną granicę zakresu na podstawie parametru "clen" (długość treści), który Google umieszcza
     * w samym URL-u — więc żadne żądanie nigdy nie jest "otwarte".
     */
    private class ClientAwareDataSourceFactory(
        private val upstream: DefaultHttpDataSource.Factory,
        private val logger: app.nuta.core.logging.NutaLogger,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            val inner = upstream.createDataSource()
            return object : DataSource by inner {
                override fun open(dataSpec: DataSpec): Long {
                    val url = dataSpec.uri.toString()
                    val itag = Regex("[?&]itag=(\\d+)").find(url)?.groupValues?.get(1)
                    inner.setRequestProperty("User-Agent", when {
                        "c=ANDROID_VR" in url -> AndroidYouTubeMediaService.VR_AGENT
                        else -> AndroidYouTubeMediaService.VISIONOS_AGENT
                    })
                    val contentLength = Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
                    // Tylko prawdziwe kontynuacje (position > 0) dostają wymuszony górny limit z "clen".
                    // Pierwsze żądanie (position == 0) zostaje bez jawnego Range, tak jak wcześniej —
                    // dodanie Range od bajtu 0 samo w sobie okazało się powodować natychmiastowe 403,
                    // czego nie było, gdy pierwsze żądanie szło bez nagłówka Range wcale.
                    val boundedSpec = if (contentLength != null && dataSpec.position > 0 && dataSpec.length == androidx.media3.common.C.LENGTH_UNSET.toLong()) {
                        dataSpec.buildUpon().setLength(contentLength - dataSpec.position).build()
                    } else dataSpec
                    logger.debug("PlaybackHttp", "range_open", "Otwieram żądanie bajtów", fields = mapOf(
                        "itag" to (itag ?: "?"),
                        "position" to dataSpec.position.toString(),
                        "requestedLength" to dataSpec.length.toString(),
                        "clen" to (contentLength?.toString() ?: "null"),
                        "boundedLength" to boundedSpec.length.toString(),
                    ))
                    return try {
                        inner.open(boundedSpec)
                    } catch (e: HttpDataSource.InvalidResponseCodeException) {
                        logger.error("PlaybackHttp", "range_open_failed", "HTTP błąd przy otwieraniu zakresu bajtów", fields = mapOf(
                            "itag" to (itag ?: "?"),
                            "responseCode" to e.responseCode.toString(),
                            "position" to dataSpec.position.toString(),
                            "requestedLength" to dataSpec.length.toString(),
                            "clen" to (contentLength?.toString() ?: "null"),
                            "boundedLength" to boundedSpec.length.toString(),
                            "responseMessage" to (e.responseMessage ?: ""),
                        ))
                        throw e
                    }
                }
            }
        }
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
        PlaybackQueueBridge.streamCache = null
        streamCache?.release()
        streamCache = null
        super.onDestroy()
    }

    companion object {
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
