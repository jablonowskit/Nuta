package app.nuta.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.nuta.AppContainer
import app.nuta.core.logging.LogLevel
import app.nuta.core.logging.MemoryLogger
import app.nuta.data.fake.FakeAudioPlayer
import app.nuta.data.fake.FakeSpotifyRepository
import app.nuta.ui.NutaApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.time.Instant

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val logger = MemoryLogger(now = { Instant.now().toString() }, initialLevel = LogLevel.DEBUG)
        val container = AppContainer(
            spotifyRepository = FakeSpotifyRepository(logger),
            audioPlayer = FakeAudioPlayer(scope, logger),
            logger = logger,
        )
        setContent { NutaApp(container) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
