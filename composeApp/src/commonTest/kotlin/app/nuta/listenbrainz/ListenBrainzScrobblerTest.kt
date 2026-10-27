package app.nuta.listenbrainz

import app.nuta.core.logging.MemoryLogger
import app.nuta.core.models.PlayerState
import app.nuta.core.models.PlayerStatus
import app.nuta.core.models.Track
import app.nuta.settings.DataSource
import app.nuta.settings.InMemoryPlaybackSettingsStore
import app.nuta.settings.YouTubePlaybackSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ListenBrainzScrobblerTest {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val states = MutableStateFlow(PlayerState())
    private var clockMs = 1_000_000_000L
    private val listens = mutableListOf<String>()
    private var failListens = 0

    private fun scrobbler() = ListenBrainzScrobbler(
        settingsStore = InMemoryPlaybackSettingsStore(
            YouTubePlaybackSettings(dataSource = DataSource.LISTENBRAINZ, listenBrainzApiToken = "test-token"),
        ),
        logger = MemoryLogger(now = { "test" }),
        nowMs = { clockMs },
        submitRequest = { _, _, body ->
            if ("\"listen_type\":\"single\"" in body) {
                if (failListens > 0) { failListens--; error("HTTP 502") }
                synchronized(listens) { listens += body }
            }
            """{"status":"ok"}"""
        },
    ).also { it.attach(states, scope) }

    private suspend fun emit(track: Track, positionMs: Long, status: PlayerStatus = PlayerStatus.PLAYING) {
        states.value = PlayerState(queue = listOf(track), currentIndex = 0, positionMs = positionMs, status = status)
        delay(3)
    }

    /** Granie co sekundę, z zegarem idącym razem z pozycją. */
    private suspend fun play(track: Track, fromMs: Long, toMs: Long) {
        var position = fromMs
        while (position <= toMs) {
            emit(track, position)
            position += 1_000
            clockMs += 1_000
        }
    }

    private fun listenedAt(body: String): Long = Regex("\"listened_at\":(\\d+)").find(body)!!.groupValues[1].toLong()

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun retriesFailedListenAndSendsItOnce() = runBlocking {
        failListens = 2
        scrobbler()
        val track = Track("id", "Title", listOf("Artist"), "", 100_000)
        play(track, 0, 60_000)
        delay(2_000)
        assertEquals(1, listens.size)
    }

    @Test
    fun positionLeftOverFromPreviousTrackDoesNotScrobbleNewOne() = runBlocking {
        scrobbler()
        val first = Track("a", "First", listOf("A"), "", 240_000)
        val second = Track("b", "Second", listOf("B"), "", 286_000)
        play(first, 0, 125_000)
        delay(200)
        assertEquals(1, listens.size)
        play(first, 126_000, 239_000)
        // przejście: nowy utwór chwilowo z pozycją z końca poprzedniego (LOADING i PLAYING)
        emit(second, 239_000, PlayerStatus.LOADING)
        emit(second, 239_000)
        delay(200)
        assertEquals(1, listens.size, "pozycja z poprzedniego utworu nie może zaliczyć nowego")
        val secondStartedAt = clockMs
        play(second, 0, 145_000)
        delay(200)
        assertEquals(2, listens.size)
        assertEquals(secondStartedAt / 1000, listenedAt(listens[1]))
    }

    @Test
    fun seekPastThresholdIsNotListening() = runBlocking {
        scrobbler()
        val track = Track("id", "Title", listOf("Artist"), "", 200_000)
        play(track, 0, 5_000)
        play(track, 150_000, 160_000)
        delay(200)
        assertEquals(0, listens.size)
    }

    @Test
    fun replayFromStartIsNewListen() = runBlocking {
        scrobbler()
        val track = Track("id", "Title", listOf("Artist"), "", 100_000)
        play(track, 0, 52_000)
        play(track, 0, 52_000)
        delay(200)
        assertEquals(2, listens.size)
    }
}
