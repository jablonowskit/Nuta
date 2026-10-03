package app.nuta.listenbrainz

import app.nuta.core.logging.MemoryLogger
import app.nuta.core.models.Track
import app.nuta.data.fake.FakeAudioPlayer
import app.nuta.settings.DataSource
import app.nuta.settings.InMemoryPlaybackSettingsStore
import app.nuta.settings.YouTubePlaybackSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ListenBrainzScrobblerTest {
    @Test
    fun retriesFailedListenAndMarksItOnlyAfterSuccess() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val player = FakeAudioPlayer(scope, MemoryLogger(now = { "test" }))
        val settings = InMemoryPlaybackSettingsStore(
            YouTubePlaybackSettings(
                dataSource = DataSource.LISTENBRAINZ,
                listenBrainzApiToken = "test-token",
            ),
        )
        var listenAttempts = 0
        val scrobbler = ListenBrainzScrobbler(
            settingsStore = settings,
            logger = MemoryLogger(now = { "test" }),
            submitRequest = { _, _, body ->
                if ("\"listen_type\":\"single\"" in body) {
                    listenAttempts++
                    if (listenAttempts < 3) error("HTTP 502")
                }
                """{"status":"ok"}"""
            },
        )
        scrobbler.attach(player, scope)
        player.setQueue(listOf(Track("id", "Title", listOf("Artist"), "", 100_000)))
        player.play()
        player.seekTo(50_000)
        delay(2_000)

        assertEquals(3, listenAttempts)
        // Kolejne ticki po sukcesie nie mogą wysłać duplikatu.
        delay(1_200)
        assertEquals(3, listenAttempts)
        scope.cancel()
    }
}
