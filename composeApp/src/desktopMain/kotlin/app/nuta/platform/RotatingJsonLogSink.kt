package app.nuta.platform

import java.io.File

class RotatingJsonLogSink(
    directory: File = File(System.getenv("NUTA_LOG_DIR") ?: "/artifacts/logs"),
    private val maxBytes: Long = 2L * 1024 * 1024,
) {
    private val logFile = File(directory, "nuta.jsonl")

    init {
        runCatching { directory.mkdirs() }
    }

    @Synchronized
    fun write(line: String) {
        runCatching {
            if (logFile.exists() && logFile.length() >= maxBytes) {
                val rotated = File(logFile.parentFile, "nuta.1.jsonl")
                if (rotated.exists()) rotated.delete()
                logFile.renameTo(rotated)
            }
            logFile.appendText(line + System.lineSeparator())
        }
    }
}
