package app.nuta.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Pobiera APK z release'u „nightly” i oddaje je systemowemu instalatorowi przez
 * [PackageInstaller] — bez FileProvidera i udostępniania pliku na zewnątrz. Android sam pyta
 * o zgodę (za pierwszym razem także o „instalowanie nieznanych aplikacji” dla Nuty). Podpis
 * pasuje do zainstalowanej wersji, bo release buduje to samo CI tym samym kluczem.
 */
object ApkInstaller {
    suspend fun install(context: Context, url: String, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val apk = File(app.cacheDir, "update.apk")
        try {
            download(url, apk, onProgress)
            val installer = app.packageManager.packageInstaller
            val sessionId = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
            installer.openSession(sessionId).use { session ->
                session.openWrite("nuta.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                // MUTABLE: instalator dopisuje do intencji status i ekran potwierdzenia
                val status = PendingIntent.getBroadcast(
                    app, sessionId, Intent(app, UpdateInstallReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(status.intentSender)
            }
        } finally {
            apk.delete()
        }
    }

    private suspend fun download(url: String, target: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        // github.com przekierowuje na serwer plików — HttpURLConnection idzie za https→https sam
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var reported = -1
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val percent = if (total > 0) (done * 100 / total).toInt() else -1
                        if (percent != reported) { reported = percent; onProgress(percent / 100f) }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}

/** Status sesji [PackageInstaller]: ekran potwierdzenia do pokazania albo wynik instalacji. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // proces i tak zostaje zastąpiony nową wersją
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                Log.w("NutaUpdate", "install_failed: $message")
                // anulowanie przez użytkownika to też „porażka” — wtedy bez komunikatu
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    Toast.makeText(context, "Aktualizacja nie powiodła się: $message", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
