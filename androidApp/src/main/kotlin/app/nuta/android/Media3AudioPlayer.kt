package app.nuta.android

import android.content.SharedPreferences
import android.util.Base64
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import app.nuta.core.logging.NutaLogger
import app.nuta.core.models.PlayerState
import app.nuta.core.models.PlayerStatus
import app.nuta.core.models.Track
import app.nuta.domain.AudioPlayer
import app.nuta.youtube.YouTubeMediaService
import app.nuta.youtube.YouTubeResolution
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
    /** Pozycja z poprzedniej sesji — użyta raz przy pierwszym odtworzeniu przywróconego utworu. */
    private var pendingResumePositionMs = queuePreferences.getLong("positionMs", 0L).coerceAtLeast(0L)
    private var lastPositionSaveMs = 0L
    private val stateFlow = MutableStateFlow(restoreQueue())
    override val state: StateFlow<PlayerState> = stateFlow.asStateFlow()
    private val loadMutex = Mutex()
    private var ticker: Job? = null
    private var retryingAfterError = false
    private val prefetchCache = ConcurrentHashMap<String, Deferred<YouTubeResolution>>()
    private val streamPreloadedIds = ConcurrentHashMap.newKeySet<String>()

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> scope.launch { advanceAfterEnd() }
                    else -> refreshPlayingState()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) startTicker()
                refreshPlayingState()
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
        PlaybackQueueBridge.onNext = { scope.launch { next() } }
        PlaybackQueueBridge.onPrevious = { scope.launch { previous() } }
        scope.launch {
            stateFlow.collect { state ->
                PlaybackQueueBridge.hasNext.value = state.currentIndex + 1 in state.queue.indices
                PlaybackQueueBridge.hasPrevious.value = state.currentIndex - 1 in state.queue.indices
            }
        }
        // jedyne źródło prawdy o LOADING/PLAYING/PAUSED: reaguje zarówno na eventy playera jak i na
        // prawdziwy stan buforowania z serwisu, niezależnie od kolejności ich napłynięcia.
        // MediaController wymaga wywołań z głównego wątku — stąd Dispatchers.Main tutaj.
        scope.launch(Dispatchers.Main) { PlaybackQueueBridge.buffering.collect { refreshPlayingState() } }
    }

    /** Jedyne miejsce, które ustawia LOADING/PLAYING/PAUSED — unika wyścigu między eventami playera a bridge.buffering. */
    private fun refreshPlayingState() {
        if (stateFlow.value.currentTrack == null) return
        when {
            PlaybackQueueBridge.buffering.value -> stateFlow.value = stateFlow.value.copy(status = PlayerStatus.LOADING)
            player.isPlaying -> stateFlow.value = stateFlow.value.copy(status = PlayerStatus.PLAYING)
            // dowolny status "w trakcie" (w tym LOADING po seeku podczas pauzy) wraca do PAUSED — nie tylko z PLAYING,
            // inaczej pauza + seek zostawiały UI zablokowane na LOADING (brak pasującej gałęzi)
            player.playbackState == Player.STATE_READY && stateFlow.value.status !in TERMINAL_STATUSES ->
                stateFlow.value = stateFlow.value.copy(status = PlayerStatus.PAUSED)
        }
    }

    override suspend fun setQueue(tracks: List<Track>, startIndex: Int) {
        require(startIndex in tracks.indices || tracks.isEmpty())
        pendingResumePositionMs = 0
        withContext(Dispatchers.Main) { player.stop(); player.clearMediaItems() }
        stateFlow.value = PlayerState(queue = tracks, currentIndex = if (tracks.isEmpty()) -1 else startIndex)
        saveQueue()
        savePosition(0)
    }
    override suspend fun appendToQueue(tracks: List<Track>) { if (tracks.isNotEmpty()) { stateFlow.value = stateFlow.value.copy(queue = stateFlow.value.queue + tracks); saveQueue() } }
    override suspend fun shuffleUpcoming() {
        val state = stateFlow.value
        if (state.currentIndex !in state.queue.indices) return
        stateFlow.value = state.copy(queue = state.queue.take(state.currentIndex + 1) + state.queue.drop(state.currentIndex + 1).shuffled(), shuffleEnabled = true)
        saveQueue()
        logger.info("Media3Player", "queue_shuffled", "Przetasowano pozostałe utwory kolejki")
    }

    override suspend fun prefetch(tracks: List<Track>) {
        if (prefetchCache.size > 40) return
        val state = stateFlow.value
        val upcomingTwoIds = (state.currentIndex + 1..state.currentIndex + 2).mapNotNull { state.queue.getOrNull(it)?.id }.toSet()
        tracks.take(PREFETCH_LIMIT).forEach { track ->
            if (track.id == state.currentTrack?.id) return@forEach
            val deferred = prefetchCache.computeIfAbsent(track.id) {
                val startedAtMs = System.currentTimeMillis()
                logger.info("Media3Player", "prefetch_started", "Rozpoczęto prefetch strumienia", fields = mapOf("track" to track.title))
                scope.async {
                    youtube.resolve(track).also {
                        logger.info("Media3Player", "prefetch_ready", "Prefetch gotowy", fields = mapOf(
                            "track" to track.title,
                            "ms" to (System.currentTimeMillis() - startedAtMs).toString(),
                        ))
                    }
                }
            }
            // dla 2 najbliższych utworów z kolejki dograj do cache pierwsze ~10 s audio
            if (track.id in upcomingTwoIds && streamPreloadedIds.add(track.id)) {
                scope.launch {
                    runCatching { deferred.await() }.getOrNull()?.let { preloadStreamStart(track, it) }
                }
            }
        }
    }

    /** Dogrywa początek strumienia do cache serwisu, żeby start odtwarzania nie czekał na sieć. */
    private suspend fun preloadStreamStart(track: Track, resolution: YouTubeResolution) {
        val factory = PlaybackQueueBridge.streamCacheFactory ?: return
        val bytes = (resolution.stream.bitrate.toLong().coerceAtLeast(96_000) / 8 * PRELOAD_SECONDS)
            .coerceAtMost(1_500_000)
        val url = resolution.stream.url.use { it }
        withContext(Dispatchers.IO) {
            runCatching {
                CacheWriter(factory.createDataSource(), DataSpec.Builder().setUri(url).setPosition(0).setLength(bytes).build(), null, null).cache()
            }.onSuccess {
                logger.info("Media3Player", "prefetch_stream_cached", "Zbuforowano początek strumienia", fields = mapOf("track" to track.title, "bytes" to bytes.toString()))
            }.onFailure { error ->
                streamPreloadedIds.remove(track.id)
                logger.warn("Media3Player", "prefetch_stream_failed", "Nie udało się zbuforować początku strumienia", fields = mapOf("track" to track.title, "reason" to (error.message ?: "unknown")))
            }
        }
    }

    /** Zużywa wpis z cache prefetchu, jeśli jest świeży; w przeciwnym razie rozwiązuje strumień normalnie. */
    private suspend fun resolveForPlayback(track: Track): YouTubeResolution {
        val cached = prefetchCache.remove(track.id)
        if (cached != null) {
            if (!cached.isCompleted) {
                logger.info("Media3Player", "prefetch_miss_pending", "Prefetch jeszcze się nie zakończył — czekam na niego zamiast startować od nowa", fields = mapOf("track" to track.title))
            }
            val resolution = runCatching { cached.await() }.getOrNull()
            val expiresAtMs = resolution?.stream?.expiresAtMs
            if (resolution != null && (expiresAtMs == null || expiresAtMs - System.currentTimeMillis() > PREFETCH_EXPIRY_MARGIN_MS)) {
                logger.info("Media3Player", "prefetch_hit", "Użyto rozwiązania z prefetchu", fields = mapOf("track" to track.title))
                return resolution
            }
            logger.info("Media3Player", "prefetch_expired", "Prefetch wygasł lub się nie udał — rozwiązuję od nowa", fields = mapOf("track" to track.title))
        } else {
            logger.info("Media3Player", "prefetch_miss", "Brak prefetchu dla utworu — rozwiązuję od zera", fields = mapOf("track" to track.title))
        }
        return youtube.resolve(track)
    }

    override suspend fun play() {
        val track = stateFlow.value.currentTrack ?: return
        if (stateFlow.value.status == PlayerStatus.PAUSED) { withContext(Dispatchers.Main) { player.play() }; return }
        loadMutex.withLock {
            // wznowienie po restarcie: pierwszy start przywróconego utworu zaczyna od zapisanej pozycji
            val resumeFromMs = pendingResumePositionMs.takeIf { it > 0 && it < track.durationMs }
            pendingResumePositionMs = 0
            stateFlow.value = stateFlow.value.copy(status = PlayerStatus.LOADING, positionMs = resumeFromMs ?: 0, errorMessage = null, streamBitrate = null, streamCodec = null)
            runCatching { resolveForPlayback(track) }.onSuccess { resolution ->
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
                    player.prepare()
                    if (resumeFromMs != null) player.seekTo(resumeFromMs)
                    player.play()
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
    override suspend fun pause() = withContext(Dispatchers.Main) { player.pause(); savePosition(player.currentPosition) }
    override suspend fun stop() { ticker?.cancel(); withContext(Dispatchers.Main) { player.stop() }; savePosition(0); stateFlow.value = stateFlow.value.copy(status = PlayerStatus.IDLE, positionMs = 0) }
    override suspend fun seekTo(positionMs: Long) { withContext(Dispatchers.Main) { player.seekTo(positionMs.coerceIn(0, stateFlow.value.durationMs)) } }
    override suspend fun next() = move(stateFlow.value.currentIndex + 1)
    override suspend fun previous() = move(stateFlow.value.currentIndex - 1)
    override suspend fun playAt(index: Int) = move(index)
    override suspend fun simulateError() { stateFlow.value = stateFlow.value.copy(status = PlayerStatus.ERROR, errorMessage = "Symulowany błąd") }

    private suspend fun move(index: Int) {
        if (index !in stateFlow.value.queue.indices) return
        pendingResumePositionMs = 0
        stateFlow.value = stateFlow.value.copy(currentIndex = index, status = PlayerStatus.IDLE, positionMs = 0, errorMessage = null, streamBitrate = null, streamCodec = null)
        saveQueue()
        savePosition(0)
        // pause zamiast stop: stop przełącza sesję w IDLE i zwija powiadomienie/lock screen na czas rozwiązywania streamu
        withContext(Dispatchers.Main) { player.pause() }
        play()
    }

    private fun saveQueue() {
        val state = stateFlow.value
        val rows = state.queue.joinToString("\n") { t -> listOf(t.id, t.title, t.artists.joinToString("\u001f"), t.album, t.durationMs.toString(), t.imageUrl.orEmpty()).joinToString("\u001e") { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) } }
        queuePreferences.edit().putString("tracks", rows).putInt("index", state.currentIndex).apply()
    }

    private fun savePosition(positionMs: Long) {
        queuePreferences.edit().putLong("positionMs", positionMs.coerceAtLeast(0)).apply()
    }

    private fun restoreQueue(): PlayerState {
        val tracks = queuePreferences.getString("tracks", "").orEmpty().lineSequence().filter(String::isNotBlank).mapNotNull { row -> runCatching {
            val v = row.split("\u001e").map { String(Base64.decode(it, Base64.DEFAULT)) }
            Track(v[0], v[1], v[2].split("\u001f"), v[3], v[4].toLong(), v[5].ifBlank { null })
        }.getOrNull() }.toList()
        val index = queuePreferences.getInt("index", -1).coerceIn(-1, tracks.lastIndex)
        return PlayerState(
            queue = tracks,
            currentIndex = index,
            positionMs = if (index >= 0) pendingResumePositionMs else 0L,
        )
    }
    private suspend fun advanceAfterEnd() { val next = stateFlow.value.currentIndex + 1; if (next in stateFlow.value.queue.indices) move(next) else stateFlow.value = stateFlow.value.copy(status = PlayerStatus.ENDED) }
    private fun startTicker() { ticker?.cancel(); ticker = scope.launch { while (isActive) { delay(500); val position = withContext(Dispatchers.Main) { player.currentPosition }; stateFlow.value = stateFlow.value.copy(positionMs = position.coerceAtLeast(0)); val now = System.currentTimeMillis(); if (now - lastPositionSaveMs > 5_000) { lastPositionSaveMs = now; savePosition(position) } } } }

    private companion object {
        const val PREFETCH_LIMIT = 5
        const val PREFETCH_EXPIRY_MARGIN_MS = 30_000L
        const val PRELOAD_SECONDS = 10L
        val TERMINAL_STATUSES = setOf(PlayerStatus.IDLE, PlayerStatus.ENDED, PlayerStatus.ERROR)
    }
}
