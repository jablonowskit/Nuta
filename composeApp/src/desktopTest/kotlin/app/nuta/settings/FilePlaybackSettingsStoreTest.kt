package app.nuta.settings

import app.nuta.core.logging.NutaLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FilePlaybackSettingsStoreTest {
    @Test
    fun migratesTokenAndPersistsLatestValidatedStateAtomically() {
        val directory = createTempDirectory("nuta-settings-test")
        val file = directory.resolve("playback-settings.json")
        Files.writeString(file, """{"fontScale":2.0,"cacheSizeMb":999,"listenBrainzApiToken":"secret"}""")
        val credentials = FakeCredentialStore()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        val store = FilePlaybackSettingsStore(scope, NoOpLogger(), file, credentials)
        assertEquals(1f, store.settings.value.fontScale)
        assertEquals(500, store.settings.value.cacheSizeMb)
        assertEquals("secret", store.settings.value.listenBrainzApiToken)
        assertEquals("secret", credentials.load("listenbrainz.api-token"))
        assertFalse(Files.readString(file).contains("secret"))

        store.update { it.copy(theme = AppTheme.EMBER) }
        store.update { it.copy(cassetteBackground = false) }
        store.flush()
        scope.cancel()

        val restored = FilePlaybackSettingsStore(
            CoroutineScope(Dispatchers.Default + SupervisorJob()),
            NoOpLogger(),
            file,
            credentials,
        )
        assertEquals(AppTheme.EMBER, restored.settings.value.theme)
        assertFalse(restored.settings.value.cassetteBackground)
        assertNull(directory.resolve("playback-settings.json.tmp").takeIf(Files::exists))
    }

    private class FakeCredentialStore : CredentialStore {
        private val values = mutableMapOf<String, String>()
        override fun load(key: String): String? = values[key]
        override fun save(key: String, value: String) { values[key] = value }
        override fun clear(key: String) { values.remove(key) }
    }

    private class NoOpLogger : NutaLogger {
        override fun trace(module: String, event: String, message: String, operationId: String, fields: Map<String, String>) = Unit
        override fun debug(module: String, event: String, message: String, operationId: String, fields: Map<String, String>) = Unit
        override fun info(module: String, event: String, message: String, operationId: String, fields: Map<String, String>) = Unit
        override fun warn(module: String, event: String, message: String, operationId: String, fields: Map<String, String>) = Unit
        override fun error(module: String, event: String, message: String, operationId: String, fields: Map<String, String>, throwable: Throwable?) = Unit
    }
}
