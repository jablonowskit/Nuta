package app.nuta.update

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppUpdaterTest {
    @Test
    fun differentShaIsUpdate() {
        assertEquals("abc1234", AppUpdater.newerVersion("abc1234\n", "98c58a0"))
        assertNull(AppUpdater.newerVersion("98c58a0", "98c58a0"))
    }

    @Test
    fun localBuildAndGarbageAreNotUpdates() {
        assertNull(AppUpdater.newerVersion("abc1234", "dev"))
        assertNull(AppUpdater.newerVersion("<html>Not Found</html>", "98c58a0"))
    }

    @Test
    fun checkThenInstallReportsProgressAndStaysAvailable() = runBlocking {
        var installedFrom: String? = null
        val updater = AppUpdater(
            install = { url, onProgress -> installedFrom = url; onProgress(0.5f); onProgress(1f) },
            fetchVersion = { "abc1234" },
            installedVersion = "98c58a0",
        )
        updater.check()
        assertEquals(UpdateState.Available("abc1234"), updater.state.value)
        updater.downloadAndInstall()
        assertEquals(AppUpdater.ApkUrl, installedFrom)
        assertEquals(UpdateState.Available("abc1234"), updater.state.value)
    }

    @Test
    fun failedFetchIsReported() = runBlocking {
        val updater = AppUpdater(install = { _, _ -> }, fetchVersion = { error("HTTP 404") }, installedVersion = "98c58a0")
        updater.check()
        assertEquals(UpdateState.Failed("HTTP 404"), updater.state.value)
    }
}
