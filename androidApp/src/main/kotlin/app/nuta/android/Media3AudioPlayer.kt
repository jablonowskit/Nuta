package app.nuta.android

import android.content.SharedPreferences
import android.util.Base64
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import app.nuta.core.logging.NutaLogger
import app.nuta.core.models.PlayerState
import app.nuta.core.models.PlayerStatus
import app.nuta.core.models.Track
import app.nuta.domain.AudioPlayer
import app.nuta.youtube.YouTubeMediaService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class Media3AudioPlayer(
    private val player: Player,
    private val scope: CoroutineScope,
    private val youtube: YouTubeMediaService,
    private val logger: NutaLogger,
    private val queuePreferences: SharedPreferences,
) : AudioPlayer {
    private val stateFlow = MutableStateFlow(restoreQueue())
    override val state: StateFlow<PlayerState> = stateFlow.asStateFlow()
    private val loadMutex = Mutex()
    private var ticker: Job? = null
    private var retryingAfterError = false

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> if (player.playWhenReady) stateFlow.value = stateFlow.value.copy(status = PlayerStatus.PLAYING)
                    Player.STATE_ENDED -> scope.launch { advanceAfterEnd() }
                    Player.STATE_BUFFERING -> if (stateFlow.value.currentTrack != null) stateFlow.value = stateFlow.value.copy(status = PlayerStatus.LOADING)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) { stateFlow.value = stateFlow.value.copy(status = PlayerStatus.PLAYING); startTicker() }
                else if (player.playbackState == Player.STATE_READY && stateFlow.value.status == PlayerStatus.PLAYING) stateFlow.value = stateFlow.value.copy(status = PlayerStatus.PAUSED)
            }
            override fun onPlayerError(error: PlaybackException) {
                stateFlow.value = stateFlow.value.copy(status = PlayerStatus.ERROR, errorMessage = error.errorCodeName)
                if (!retryingAfterError && stateFlow.value.currentTrack != null) {
                    retryingAfterError = true
                    scope.launch {
                        delay(250)
                        withContext(Dispatchers.Main) { player.stop(); player.clearMediaItems() }
                        play()
                    }
                }
                logger.error("Media3Player", "playback_failed", "Media3 zgłosił błąd odtwarzania", throwable = error)
            }
        })
    }

    override suspend fun setQueue(tracks: List<Track>, startIndex: Int) {
        require(startIndex in tracks.indices || tracks.isEmpty())
        withContext(Dispatchers.Main) { player.stop(); player.clearMediaItems() }
        stateFlow.value = PlayerState(queue = tracks, currentIndex = if (tracks.isEmpty()) -1 else startIndex)
        saveQueue()
    }
    override suspend fun appendToQueue(tracks: List<Track>) { if (tracks.isNotEmpty()) { stateFlow.value = stateFlow.value.copy(queue = stateFlow.value.queue + tracks); saveQueue() } }
    override suspend fun shuffleUpcoming() {
        val state = stateFlow.value
        if (state.currentIndex !in state.queue.indices) return
        stateFlow.value = state.copy(queue = state.queue.take(state.currentIndex + 1) + state.queue.drop(state.currentIndex + 1).shuffled(), shuffleEnabled = true)
        saveQueue()
        logger.info("Media3Player", "queue_shuffled", "Przetasowano pozostałe utwory kolejki")
    }

    override suspend fun play() {
        val track = stateFlow.value.currentTrack ?: return
        if (stateFlow.value.status == PlayerStatus.PAUSED) { withContext(Dispatchers.Main) { player.play() }; return }
        loadMutex.withLock {
            stateFlow.value = stateFlow.value.copy(status = PlayerStatus.LOADING, positionMs = 0, errorMessage = null, streamBitrate = null, streamCodec = null)
            runCatching { youtube.resolve(track) }.onSuccess { resolution ->
                retryingAfterError = false
                val url = resolution.stream.url.use { it }
                stateFlow.value = stateFlow.value.copy(
                    streamBitrate = resolution.stream.bitrate,
                    streamCodec = resolution.stream.codec,
                )
                withContext(Dispatchers.Main) {
                    player.setMediaItem(MediaItem.Builder()
                        .setUri(url)
                        .setMediaMetadata(MediaMetadata.Builder()
                            .setTitle(track.title)
                            .setArtist(track.artists.joinToString())
                            .setAlbumTitle(track.album)
                            .build())
                        .build())
                    player.prepare(); player.play()
                }
                logger.info("Media3Player", "playback_prepared", "Przekazano strumień YouTube do Media3", fields = mapOf(
                    "codec" to resolution.stream.codec,
                    "mimeType" to resolution.stream.mimeType,
                    "bitrate" to resolution.stream.bitrate.toString(),
                ))
            }.onFailure { error ->
                stateFlow.value = stateFlow.value.copy(status = PlayerStatus.ERROR, errorMessage = error.message)
                logger.error("Media3Player", "resolution_failed", "Nie udało się przygotować strumienia YouTube", throwable = error)
            }
        }
    }
    override suspend fun pause() = withContext(Dispatchers.Main) { player.pause() }
    override suspend fun stop() { ticker?.cancel(); withContext(Dispatchers.Main) { player.stop() }; stateFlow.value = stateFlow.value.copy(status = PlayerStatus.IDLE, positionMs = 0) }
    override suspend fun seekTo(positionMs: Long) { withContext(Dispatchers.Main) { player.seekTo(positionMs.coerceIn(0, stateFlow.value.durationMs)) } }
    override suspend fun next() = move(stateFlow.value.currentIndex + 1)
    override suspend fun previous() = move(stateFlow.value.currentIndex - 1)
    override suspend fun playAt(index: Int) = move(index)
    override suspend fun simulateError() { stateFlow.value = stateFlow.value.copy(status = PlayerStatus.ERROR, errorMessage = "Symulowany błąd") }

    private suspend fun move(index: Int) {
        if (index !in stateFlow.value.queue.indices) return
        stateFlow.value = stateFlow.value.copy(currentIndex = index, status = PlayerStatus.IDLE, positionMs = 0, errorMessage = null, streamBitrate = null, streamCodec = null)
        withContext(Dispatchers.Main) { player.stop() }
        play()
    }

    private fun saveQueue() {
        val state = stateFlow.value
        val rows = state.queue.joinToString("\n") { t -> listOf(t.id, t.title, t.artists.joinToString("\u001f"), t.album, t.durationMs.toString(), t.imageUrl.orEmpty()).joinToString("\u001e") { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) } }
        queuePreferences.edit().putString("tracks", rows).putInt("index", state.currentIndex).apply()
    }

    private fun restoreQueue(): PlayerState {
        val tracks = queuePreferences.getString("tracks", "").orEmpty().lineSequence().filter(String::isNotBlank).mapNotNull { row -> runCatching {
            val v = row.split("\u001e").map { String(Base64.decode(it, Base64.DEFAULT)) }
            Track(v[0], v[1], v[2].split("\u001f"), v[3], v[4].toLong(), v[5].ifBlank { null })
        }.getOrNull() }.toList()
        return PlayerState(queue = tracks, currentIndex = queuePreferences.getInt("index", -1).coerceIn(-1, tracks.lastIndex))
    }
    private suspend fun advanceAfterEnd() { val next = stateFlow.value.currentIndex + 1; if (next in stateFlow.value.queue.indices) move(next) else stateFlow.value = stateFlow.value.copy(status = PlayerStatus.ENDED) }
    private fun startTicker() { ticker?.cancel(); ticker = scope.launch { while (isActive) { delay(500); val position = withContext(Dispatchers.Main) { player.currentPosition }; stateFlow.value = stateFlow.value.copy(positionMs = position.coerceAtLeast(0)) } } }
}
