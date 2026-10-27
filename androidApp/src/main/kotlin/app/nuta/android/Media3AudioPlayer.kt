package app.nuta.android

import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.session.MediaController
import app.nuta.core.logging.NutaLogger
import app.nuta.core.models.PlayerState
import app.nuta.core.models.PlayerStatus
import app.nuta.core.models.Track
import app.nuta.core.models.withInsertedNext
import app.nuta.core.models.withMoved
import app.nuta.core.models.withResolvedDuration
import app.nuta.domain.AudioPlayer
import app.nuta.settings.PlaybackSettingsStore
import app.nuta.youtube.YouTubeMediaService
import app.nuta.youtube.YouTubeResolution
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withContext

class Media3AudioPlayer(
    private val player: Player,
    private val scope: CoroutineScope,
    private val youtube: YouTubeMediaService,
    private val logger: NutaLogger,
    private val queuePreferences: SharedPreferences,
    private val settingsStore: PlaybackSettingsStore,
) : AudioPlayer {
    /** Pozycja z poprzedniej sesji — użyta raz przy pierwszym odtworzeniu przywróconego utworu. */
    private var pendingResumePositionMs = queuePreferences.getLong("positionMs", 0L).coerceAtLeast(0L)
    private var lastPositionSaveMs = 0L
    private val stateFlow = MutableStateFlow(restoreQueue())
    override val state: StateFlow<PlayerState> = stateFlow.asStateFlow()
    private val loadLock = Any()
    private var loadJob: Job? = null
    private var playbackGeneration = 0L
    private var ticker: Job? = null
    /** Rośnie przy każdym [startTicker]; pozwala starej korutynie rozpoznać, że jest już nieaktualna. */
    private var tickerGeneration = 0
    private var retryingAfterError = false
    /** Reset retryingAfterError czeka na STABLE_PLAYBACK_MS realnie lecącego dźwięku, nie na samo
        chwilowe player.isPlaying == true zaraz po udanym rozwiązaniu URL-a. Bez tego utwór, który
        pada kilka sekund po starcie (typowe dla wygasających/blokowanych URLi YouTube z HTTP 403),
        zdążył na moment "zagrać", zerując flagę PRZED wystąpieniem błędu — retry nigdy się nie
        wysycał i player w kółko odtwarzał ten sam zepsuty utwór (resolve → play → 403 → resolve...). */
    private var stabilityJob: Job? = null
    /**
     * Wpisy trzymają żywe [Deferred], więc mapa nie może rosnąć bez końca — utwory, których
     * użytkownik nigdy nie odtworzy, zostawały w niej na cały czas życia procesu. Kolejność
     * wstawiania z LinkedHashMap pozwala eksmitować najstarsze zamiast blokować nowe prefetche.
     * Dostęp zawsze pod [prefetchLock].
     */
    private val prefetchCache = LinkedHashMap<String, Deferred<YouTubeResolution>>()
    private val prefetchLock = Any()
    private var released = false
    private var preloadJob: Job? = null
    /**
     * Pauza przyszła, zanim [loadTrack] skończył resolve — wtedy nie wolno mu na końcu wołać
     * play(). Inaczej wyłącznik „do końca utworu” (pauza tuż po przejściu na następny) i pauza
     * użytkownika w trakcie ładowania przegrywały z resolve i muzyka grała dalej.
     * Zeruje ją tylko świadoma akcja użytkownika (play/next/previous/playAt), nie auto-przejście.
     */
    @Volatile private var pauseRequested = false
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> scope.launch { advanceAfterEnd() }
                else -> refreshPlayingState()
            }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Zatrzymanie tickera na pauzie/stopie: wcześniej biegał dalej i co pół sekundy
            // przepisywał pozycję oraz zapisywał ją do preferencji, mimo że nic nie grało.
            if (isPlaying) startTicker() else stopTicker()
            refreshPlayingState()
        }
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            val position = newPosition.positionMs.coerceAtLeast(0)
            stateFlow.value = stateFlow.value.copy(positionMs = position)
            savePosition(position)
        }
        override fun onPlayerError(error: PlaybackException) {
            // musi pójść PRZED odczytem retryingAfterError — inaczej opóźnione zerowanie ze
            // stabilityJob (zaplanowane, gdy dźwięk na chwilę ruszył) mogło wskoczyć między
            // start błędu a ten odczyt i zresetować flagę tuż przed jej użyciem
            cancelStabilityJob()
            stateFlow.value = stateFlow.value.copy(status = PlayerStatus.ERROR, errorMessage = error.errorCodeName)
            if (!retryingAfterError && stateFlow.value.currentTrack != null) {
                retryingAfterError = true
                scope.launch {
                    delay(250)
                    withContext(Dispatchers.Main) {
                        // bez tego retry po chwilowym błędzie sieci zaczynał utwór od 0:00
                        pendingResumePositionMs = player.currentPosition.takeIf { it > 0 } ?: stateFlow.value.positionMs
                        player.stop(); player.clearMediaItems()
                    }
                    startPlayback()
                }
            }
            logger.error("Media3Player", "playback_failed", "Media3 zgłosił błąd odtwarzania", throwable = error)
        }
    }
    private var bridgeQueueJob: Job? = null
    private var bridgeBufferingJob: Job? = null

    init {
        player.addListener(playerListener)
        PlaybackQueueBridge.onNext = { scope.launch { next() } }
        PlaybackQueueBridge.onPrevious = { scope.launch { previous() } }
        bridgeQueueJob = scope.launch {
            stateFlow.collect { state ->
                PlaybackQueueBridge.hasNext.value = state.currentIndex + 1 in state.queue.indices
                PlaybackQueueBridge.hasPrevious.value = state.currentIndex - 1 in state.queue.indices
            }
        }
        // jedyne źródło prawdy o LOADING/PLAYING/PAUSED: reaguje zarówno na eventy playera jak i na
        // prawdziwy stan buforowania z serwisu, niezależnie od kolejności ich napłynięcia.
        // MediaController wymaga wywołań z głównego wątku — stąd Dispatchers.Main tutaj.
        bridgeBufferingJob = scope.launch(Dispatchers.Main) {
            PlaybackQueueBridge.buffering.collect { refreshPlayingState() }
        }
        // Po reconnect MediaController ExoPlayer w serwisie może już grać — bez sync UI zostaje
        // przy IDLE/pozycji z prefs, mimo że dźwięk leci (przegląd reconnect 2026-10-03).
        scope.launch(Dispatchers.Main) { syncFromPlayer() }
    }

    /**
     * Zwalnia listenery i korutyny tego wrappera.
     * @param releaseController true przy podmianie na nowy MediaController; false w
     *   `onDisconnected`, gdzie kontroler jest już w trakcie zwalniania przez sesję.
     */
    fun release(releaseController: Boolean = true) {
        if (released) return
        released = true
        cancelLoad()
        stopTicker()
        cancelStabilityJob()
        preloadJob?.cancel()
        bridgeQueueJob?.cancel()
        bridgeBufferingJob?.cancel()
        synchronized(prefetchLock) {
            prefetchCache.values.forEach { it.cancel() }
            prefetchCache.clear()
        }
        player.removeListener(playerListener)
        // AppServices woła release() PRZED utworzeniem nowego playera, więc bridge jest jeszcze nasz.
        PlaybackQueueBridge.onNext = null
        PlaybackQueueBridge.onPrevious = null
        if (releaseController) {
            runCatching { (player as? MediaController)?.release() }
        }
    }

    /** Dopasowuje stateFlow do aktualnego ExoPlayera (po reconnect / cold start z żywą sesją). */
    private fun syncFromPlayer() {
        if (released) return
        val mediaId = player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
        val position = player.currentPosition.coerceAtLeast(0)
        val state = stateFlow.value
        val index = when {
            mediaId != null -> state.queue.indexOfFirst { it.id == mediaId }.takeIf { it >= 0 }
            else -> null
        } ?: state.currentIndex
        if (index !in state.queue.indices && player.mediaItemCount == 0) return
        val status = when {
            PlaybackQueueBridge.buffering.value -> PlayerStatus.LOADING
            player.isPlaying -> PlayerStatus.PLAYING
            player.playbackState == Player.STATE_READY -> PlayerStatus.PAUSED
            player.playbackState == Player.STATE_ENDED -> PlayerStatus.ENDED
            player.mediaItemCount > 0 -> PlayerStatus.LOADING
            else -> state.status
        }
        if (player.isPlaying) {
            pendingResumePositionMs = 0
            startTicker()
        }
        stateFlow.value = state.copy(
            currentIndex = if (index in state.queue.indices) index else state.currentIndex,
            positionMs = if (player.mediaItemCount > 0) position else state.positionMs,
            status = status,
            errorMessage = null,
        )
        if (player.mediaItemCount > 0) savePosition(position)
        logger.info(
            "Media3Player",
            "synced_from_player",
            "Zsynchronizowano stan UI z ExoPlayerem",
            fields = mapOf(
                "status" to status.name,
                "positionMs" to position.toString(),
                "mediaId" to (mediaId ?: ""),
            ),
        )
    }

    /** Jedyne miejsce, które ustawia LOADING/PLAYING/PAUSED — unika wyścigu między eventami playera a bridge.buffering. */
    private fun refreshPlayingState() {
        if (stateFlow.value.currentTrack == null) return
        when {
            PlaybackQueueBridge.buffering.value -> {
                cancelStabilityJob()
                stateFlow.value = stateFlow.value.copy(status = PlayerStatus.LOADING)
            }
            player.isPlaying -> {
                if (stabilityJob?.isActive != true) {
                    stabilityJob = scope.launch { delay(STABLE_PLAYBACK_MS); retryingAfterError = false }
                }
                stateFlow.value = stateFlow.value.copy(status = PlayerStatus.PLAYING)
            }
            // dowolny status "w trakcie" (w tym LOADING po seeku podczas pauzy) wraca do PAUSED — nie tylko z PLAYING,
            // inaczej pauza + seek zostawiały UI zablokowane na LOADING (brak pasującej gałęzi)
            player.playbackState == Player.STATE_READY && stateFlow.value.status !in TERMINAL_STATUSES -> {
                cancelStabilityJob()
                stateFlow.value = stateFlow.value.copy(status = PlayerStatus.PAUSED)
            }
        }
    }

    private fun cancelStabilityJob() { stabilityJob?.cancel(); stabilityJob = null }

    override suspend fun setQueue(tracks: List<Track>, startIndex: Int) {
        require(startIndex in tracks.indices || tracks.isEmpty())
        cancelLoad()
        pendingResumePositionMs = 0
        withContext(Dispatchers.Main) { player.stop(); player.clearMediaItems() }
        stateFlow.value = PlayerState(queue = tracks, currentIndex = if (tracks.isEmpty()) -1 else startIndex)
        saveQueue()
        savePosition(0)
    }
    override suspend fun insertNext(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        stateFlow.value = stateFlow.value.withInsertedNext(tracks)
        saveQueue()
    }
    override suspend fun moveInQueue(from: Int, to: Int) {
        stateFlow.value = stateFlow.value.withMoved(from, to)
        saveQueue()
    }
    override suspend fun appendToQueue(tracks: List<Track>) { if (tracks.isNotEmpty()) { stateFlow.value = stateFlow.value.copy(queue = stateFlow.value.queue + tracks); saveQueue() } }

    override suspend fun removeFromQueue(index: Int) {
        val state = stateFlow.value
        if (index !in state.queue.indices) return
        val newQueue = state.queue.toMutableList().apply { removeAt(index) }
        if (newQueue.isEmpty()) { clearQueue(); return }
        when {
            index < state.currentIndex -> {
                // utwór przed aktualnym: samo przesunięcie indeksu, bez dotykania playera
                stateFlow.value = state.copy(queue = newQueue, currentIndex = state.currentIndex - 1)
                saveQueue()
            }
            index == state.currentIndex -> {
                val wasPlaying = state.status == PlayerStatus.PLAYING || state.status == PlayerStatus.LOADING
                val newIndex = index.coerceAtMost(newQueue.lastIndex)
                cancelLoad()
                pendingResumePositionMs = 0
                withContext(Dispatchers.Main) { player.stop(); player.clearMediaItems() }
                stateFlow.value = PlayerState(queue = newQueue, currentIndex = newIndex)
                saveQueue(); savePosition(0)
                if (wasPlaying) startPlayback()
            }
            else -> {
                // utwór po aktualnym: nie wpływa na to, co gra teraz
                stateFlow.value = state.copy(queue = newQueue)
                saveQueue()
            }
        }
    }

    override suspend fun clearQueue() {
        cancelLoad()
        pendingResumePositionMs = 0
        ticker?.cancel()
        withContext(Dispatchers.Main) { player.stop(); player.clearMediaItems() }
        stateFlow.value = PlayerState()
        saveQueue()
        savePosition(0)
    }
    override suspend fun shuffleUpcoming() {
        val state = stateFlow.value
        if (state.currentIndex !in state.queue.indices) return
        stateFlow.value = state.copy(queue = state.queue.take(state.currentIndex + 1) + state.queue.drop(state.currentIndex + 1).shuffled(), shuffleEnabled = true)
        saveQueue()
        logger.info("Media3Player", "queue_shuffled", "Przetasowano pozostałe utwory kolejki")
    }

    override suspend fun prefetch(tracks: List<Track>) {
        val state = stateFlow.value
        tracks.take(PREFETCH_LIMIT).forEach { track ->
            if (track.id == state.currentTrack?.id) return@forEach
            synchronized(prefetchLock) {
                if (prefetchCache.containsKey(track.id)) return@synchronized
                val startedAtMs = System.currentTimeMillis()
                logger.info("Media3Player", "prefetch_started", "Rozpoczęto prefetch strumienia", fields = mapOf("track" to track.title))
                prefetchCache[track.id] = scope.async {
                    youtube.resolve(track).also {
                        logger.info("Media3Player", "prefetch_ready", "Prefetch gotowy", fields = mapOf(
                            "track" to track.title,
                            "ms" to (System.currentTimeMillis() - startedAtMs).toString(),
                        ))
                    }
                }
                evictOldestPrefetchesLocked()
            }
        }
    }

    /**
     * Po starcie utworu przygotowuje następny z kolejki: adres (prefetch) i początek dźwięku.
     * Woła to sam player, nie UI — prefetch z UI zależy od widocznej listy i staje przy
     * wygaszonym ekranie (kompozycja nie dostaje klatek), a wtedy najczęściej leci muzyka.
     * Tylko jeden następny utwór: bajty dla kilku marnowałyby transfer przy przeskakiwaniu.
     */
    private fun preloadNextTrack() {
        if (!settingsStore.settings.value.prefetchEnabled) return
        val state = stateFlow.value
        val next = state.queue.getOrNull(state.currentIndex + 1) ?: return
        preloadJob?.cancel()
        preloadJob = scope.launch(Dispatchers.IO) {
            prefetch(listOf(next))
            val pending = synchronized(prefetchLock) { prefetchCache[next.id] } ?: return@launch
            val resolution = runCatching { pending.await() }.getOrNull() ?: return@launch
            preloadStreamStart(next, resolution.stream.url.use { it })
        }
    }

    /**
     * Wczytuje początek strumienia do cache'u ExoPlayera. Sam prefetch adresu nie wystarczał:
     * przejście między utworami i tak trwało ~0,8 s (zmierzone 27.10.2026, światłowód) — prawie
     * cały ten czas to pierwsza odpowiedź googlevideo. Z bajtami w cache'u start idzie z dysku.
     *
     * Żądanie od bajtu 0 bez Range, dokładnie jak pierwsze żądanie playera (patrz
     * ClientAwareDataSourceFactory — Range od 0 dawał 403); czytamy tylko [PRELOAD_BYTES] i
     * zamykamy, a CacheDataSource zapisuje przeczytany fragment. Klucz cache'u to URL, ten sam,
     * którego player użyje po prefetch_hit.
     */
    private fun preloadStreamStart(track: Track, url: String) {
        val factory = PlaybackQueueBridge.cachingDataSourceFactory ?: return
        val startedAtMs = System.currentTimeMillis()
        val source = factory.createDataSource()
        runCatching {
            source.open(DataSpec(Uri.parse(url)))
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (total < PRELOAD_BYTES) {
                val read = source.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                total += read
            }
            total
        }.onSuccess { total ->
            logger.info("Media3Player", "preload_ready", "Wczytano początek następnego utworu do cache'u", fields = mapOf(
                "track" to track.title,
                "bytes" to total.toString(),
                "ms" to (System.currentTimeMillis() - startedAtMs).toString(),
            ))
        }.onFailure { error ->
            logger.warn("Media3Player", "preload_failed", "Nie udało się wczytać początku utworu", fields = mapOf(
                "track" to track.title,
                "reason" to (error.message ?: error::class.simpleName.orEmpty()),
            ))
        }
        runCatching { source.close() }
    }

    /** Wymaga trzymania [prefetchLock]. */
    private fun evictOldestPrefetchesLocked() {
        while (prefetchCache.size > PREFETCH_CACHE_LIMIT) {
            val oldest = prefetchCache.entries.firstOrNull() ?: return
            prefetchCache.remove(oldest.key)
            // Anulowanie zwalnia korutynę i połączenie sieciowe wiszące za nieużytym prefetchem.
            oldest.value.cancel()
            logger.debug("Media3Player", "prefetch_evicted", "Usunięto najstarszy wpis prefetchu", fields = mapOf("trackId" to oldest.key))
        }
    }

    override suspend fun cacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        PlaybackQueueBridge.streamCache?.cacheSpace ?: 0L
    }

    override suspend fun clearCache() = withContext(Dispatchers.IO) {
        val cache = PlaybackQueueBridge.streamCache ?: return@withContext
        // usuwanie klucz po kluczu zamiast kasowania plików bezpośrednio — SimpleCache trzyma
        // własny indeks metadanych, więc ręczna ingerencja w katalog na dysku by go rozsynchronizowała
        cache.keys.toList().forEach { key -> runCatching { cache.removeResource(key) } }
        logger.info("Media3Player", "cache_cleared", "Wyczyszczono cache zbuforowanych strumieni")
    }

    /** Zużywa wpis z cache prefetchu, jeśli jest świeży; w przeciwnym razie rozwiązuje strumień normalnie. */
    private suspend fun resolveForPlayback(track: Track): YouTubeResolution {
        val cached = synchronized(prefetchLock) { prefetchCache.remove(track.id) }
        if (cached != null) {
            if (!cached.isCompleted) {
                logger.info("Media3Player", "prefetch_miss_pending", "Prefetch jeszcze się nie zakończył — czekam na niego zamiast startować od nowa", fields = mapOf("track" to track.title))
            }
            // Anulowanie samego prefetchu (eksmisja przez evictOldestPrefetchesLocked) jest tu
            // normalną ścieżką — wtedy po prostu rozwiązujemy strumień od nowa. Ale anulowanie
            // scope'u wywołującego musi lecieć dalej, inaczej startowalibyśmy nowe żądanie
            // sieciowe w już anulowanej korutynie.
            val resolution = try {
                cached.await()
            } catch (error: CancellationException) {
                if (!cached.isCancelled) throw error
                null
            } catch (_: Throwable) {
                null
            }
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
        pauseRequested = false
        startPlayback()
    }

    private suspend fun startPlayback() {
        val track = stateFlow.value.currentTrack ?: return
        if (stateFlow.value.status == PlayerStatus.PAUSED) { withContext(Dispatchers.Main) { player.play() }; return }
        val job = synchronized(loadLock) {
            val generation = ++playbackGeneration
            loadJob?.cancel()
            scope.launch { loadTrack(track, generation) }.also { loadJob = it }
        }
        job.join()
    }

    private suspend fun loadTrack(track: Track, generation: Long) {
        try {
            val savedPosition = pendingResumePositionMs
            val resumeFromMs = savedPosition.takeIf { it > 0 && (track.durationMs <= 0 || it < track.durationMs) }
            pendingResumePositionMs = 0
            if (!isCurrentLoad(generation, track.id)) return
            stateFlow.value = stateFlow.value.copy(
                status = PlayerStatus.LOADING,
                positionMs = resumeFromMs ?: 0,
                errorMessage = null,
                streamBitrate = null,
                streamCodec = null,
            )
            // Chip Samsunga czyta MediaItem z sesji, a resolve YouTube trwa sekundy — bez tej
            // podmiany w pasku zostaje poprzedni utwór (ZZ Top przy Limahlu, 2026-10-03).
            withContext(Dispatchers.Main) {
                if (!isCurrentLoad(generation, track.id)) return@withContext
                publishSessionMetadata(track)
            }
            val resolution = resolveForPlayback(track)
            if (!isCurrentLoad(generation, track.id)) return
            val url = resolution.stream.url.use { it }
            stateFlow.value = stateFlow.value.copy(
                streamBitrate = resolution.stream.bitrate,
                streamCodec = resolution.stream.codec,
            ).withResolvedDuration(track.id, resolution.match.candidate.durationMs)
            withContext(Dispatchers.Main) {
                if (!isCurrentLoad(generation, track.id)) return@withContext
                val item = MediaItem.Builder()
                    .setMediaId(track.id)
                    .setUri(url)
                    .setMediaMetadata(NotificationArtwork.metadata(track, settingsStore.settings.value.loadCovers))
                    .build()
                // Pozycja w setMediaItem, nie seekTo po prepare: MediaController odrzuca seekTo bez
                // słowa, dopóki sesja nie potwierdzi COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM dla nowego
                // itemu — wznowienie po restarcie zaczynało od 0:00 (27.10.2026).
                if (resumeFromMs != null) player.setMediaItem(item, resumeFromMs) else player.setMediaItem(item)
                player.prepare()
                if (!pauseRequested) player.play()
            }
            logger.info("Media3Player", "playback_prepared", "Przekazano strumień YouTube do Media3", fields = mapOf(
                "codec" to resolution.stream.codec,
                "mimeType" to resolution.stream.mimeType,
                "bitrate" to resolution.stream.bitrate.toString(),
            ))
            preloadNextTrack()
        } catch (_: CancellationException) {
            // Zmiana kolejki/utworu świadomie anuluje resolve; to nie jest błąd odtwarzania.
        } catch (error: Throwable) {
            if (!isCurrentLoad(generation, track.id)) return
            stateFlow.value = stateFlow.value.copy(status = PlayerStatus.ERROR, errorMessage = error.message)
            logger.error("Media3Player", "resolution_failed", "Nie udało się przygotować strumienia YouTube", throwable = error)
        }
    }

    private fun isCurrentLoad(generation: Long, trackId: String): Boolean =
        synchronized(loadLock) {
            generation == playbackGeneration && stateFlow.value.currentTrack?.id == trackId
        }

    /** Podmienia tylko metadane bieżącego itemu — strumień zostaje, chip w pasku już się zmienia. */
    private fun publishSessionMetadata(track: Track) {
        val metadata = NotificationArtwork.metadata(track, settingsStore.settings.value.loadCovers)
        if (player.mediaItemCount > 0) {
            val index = player.currentMediaItemIndex.coerceAtLeast(0)
            val current = player.getMediaItemAt(index)
            player.replaceMediaItem(index, current.buildUpon().setMediaId(track.id).setMediaMetadata(metadata).build())
        } else {
            // Sesja odrzuca MediaItem bez URI (UnsupportedOperationException w MediaSessionStub,
            // potwierdzone logcatem 27.10.2026) — wtedy chip/powiadomienie zostawały przy starym
            // utworze aż do końca resolve. Atrapa nie jest ładowana: player stoi w IDLE do prepare()
            // z prawdziwym strumieniem w loadTrack.
            player.setMediaItem(MediaItem.Builder().setMediaId(track.id).setUri(PendingStreamUri).setMediaMetadata(metadata).build())
        }
    }

    private fun cancelLoad() {
        synchronized(loadLock) {
            playbackGeneration++
            loadJob?.cancel()
            loadJob = null
        }
    }

    override suspend fun pause() = withContext(Dispatchers.Main) {
        pauseRequested = true
        player.pause()
        // w trakcie ładowania player trzyma jeszcze poprzedni utwór — jego pozycja byłaby bzdurą
        if (stateFlow.value.status == PlayerStatus.LOADING || stateFlow.value.status == PlayerStatus.IDLE) return@withContext
        val position = player.currentPosition.coerceAtLeast(0)
        stateFlow.value = stateFlow.value.copy(positionMs = position)
        savePosition(position)
    }
    override suspend fun stop() {
        cancelLoad()
        stopTicker()
        withContext(Dispatchers.Main) { player.stop() }
        savePosition(0)
        stateFlow.value = stateFlow.value.copy(status = PlayerStatus.IDLE, positionMs = 0)
    }
    override suspend fun seekTo(positionMs: Long) {
        // durationMs bywa 0, gdy katalog źródła nie podał długości utworu (np. wynik
        // MusicBrainz z length=null, utwór z lb-radio bez pola duration) — przycinanie do
        // 0..0 zawsze wracałoby na początek, mimo że sam strumień faktycznie da się przewijać.
        val duration = stateFlow.value.durationMs
        val target = if (duration > 0) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
        withContext(Dispatchers.Main) { player.seekTo(target) }
        stateFlow.value = stateFlow.value.copy(positionMs = target)
        savePosition(target)
    }
    override suspend fun next() { pauseRequested = false; move(stateFlow.value.currentIndex + 1) }
    override suspend fun previous() { pauseRequested = false; move(stateFlow.value.currentIndex - 1) }
    override suspend fun playAt(index: Int) { pauseRequested = false; move(index) }
    override suspend fun simulateError() { stateFlow.value = stateFlow.value.copy(status = PlayerStatus.ERROR, errorMessage = "Symulowany błąd") }

    private suspend fun move(index: Int) {
        if (index !in stateFlow.value.queue.indices) return
        cancelLoad()
        pendingResumePositionMs = 0
        stateFlow.value = stateFlow.value.copy(currentIndex = index, status = PlayerStatus.IDLE, positionMs = 0, errorMessage = null, streamBitrate = null, streamCodec = null)
        saveQueue()
        savePosition(0)
        // pause zamiast stop: stop przełącza sesję w IDLE i zwija powiadomienie/lock screen na czas rozwiązywania streamu
        withContext(Dispatchers.Main) { player.pause() }
        startPlayback()
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
    private suspend fun advanceAfterEnd() {
        val next = stateFlow.value.currentIndex + 1
        if (next in stateFlow.value.queue.indices) move(next) else {
            savePosition(stateFlow.value.positionMs)
            stateFlow.value = stateFlow.value.copy(status = PlayerStatus.ENDED)
        }
    }
    /** Kończy ticker i unieważnia go, żeby korutyna w locie nie dokonała już zapisu. */
    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
        tickerGeneration++
    }

    /**
     * Ticker pozycji. Każdy start dostaje własny numer generacji, bo `ticker?.cancel()` **nie
     * czeka** na zakończenie poprzedniej korutyny: stara mogła już wisieć w
     * `withContext(Dispatchers.Main) { player.currentPosition }` i po powrocie nadpisywała
     * `positionMs` pozycją POPRZEDNIEGO utworu, wyzerowaną chwilę wcześniej przez [move].
     *
     * Skutek w praktyce (potwierdzony na prawdziwych danych z ListenBrainz 09.09.2026): utwór
     * „Chandelier" (215 s) dostał pozycję 241 s odziedziczoną po „United" (240 s), więc próg
     * scrobblowania (107 s) był spełniony natychmiast po starcie — odsłuchanie zgłoszono po
     * 0 sekundach faktycznego słuchania, z błędnym `listened_at` sprzed czterech minut.
     */
    private fun startTicker() {
        ticker?.cancel()
        val generation = ++tickerGeneration
        ticker = scope.launch {
            while (isActive) {
                delay(500)
                val position = withContext(Dispatchers.Main) { player.currentPosition }
                // Zapis tylko jeśli w trakcie oczekiwania nie wystartował nowszy ticker.
                if (generation != tickerGeneration) return@launch
                stateFlow.value = stateFlow.value.copy(positionMs = position.coerceAtLeast(0))
                val now = System.currentTimeMillis()
                if (now - lastPositionSaveMs > 5_000) { lastPositionSaveMs = now; savePosition(position) }
            }
        }
    }

    private companion object {
        const val PREFETCH_LIMIT = 5
        /** ~13 s Opus przy 160 kb/s — z zapasem na start, zanim dociągnie się reszta z sieci. */
        const val PRELOAD_BYTES = 256 * 1024
        const val PREFETCH_CACHE_LIMIT = 40
        const val PREFETCH_EXPIRY_MARGIN_MS = 30_000L
        const val STABLE_PLAYBACK_MS = 8_000L
        /** Atrapa URI dla wczesnych metadanych — patrz publishSessionMetadata; nigdy nie ładowana. */
        const val PendingStreamUri = "nuta://pending-stream"
        val TERMINAL_STATUSES = setOf(PlayerStatus.IDLE, PlayerStatus.ENDED, PlayerStatus.ERROR)
    }
}
