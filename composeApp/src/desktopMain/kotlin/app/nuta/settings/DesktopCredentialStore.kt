package app.nuta.settings

import app.nuta.core.logging.NutaLogger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import java.util.Properties

/**
 * Windows używa DPAPI powiązanego z bieżącym kontem. Na pozostałych systemach sekret trafia
 * do osobnego pliku dostępnego wyłącznie właścicielowi (integracja z systemowym keyringiem może
 * zostać dodana bez zmiany formatu głównego pliku ustawień).
 */
class DesktopCredentialStore(
    private val file: Path,
    private val logger: NutaLogger,
) : CredentialStore {
    private val lock = Any()

    override fun load(key: String): String? = synchronized(lock) {
        runCatching {
            if (!Files.exists(file)) return null
            val properties = Properties().apply { Files.newInputStream(file).use(::load) }
            val stored = properties.getProperty(key) ?: return null
            when {
                stored.startsWith(DpapiPrefix) -> dpapi(stored.removePrefix(DpapiPrefix), protect = false)
                stored.startsWith(PlainPrefix) -> decode(stored.removePrefix(PlainPrefix))
                else -> null
            }
        }.onFailure {
            logger.warn("Credentials", "credential_load_failed", "Nie udało się odczytać poświadczenia")
        }.getOrNull()
    }

    override fun save(key: String, value: String) {
        synchronized(lock) {
            if (value.isBlank()) {
                clear(key)
                return@synchronized
            }
            runCatching {
                val properties = readProperties()
                val encoded = encode(value)
                val stored = if (isWindows()) DpapiPrefix + requireNotNull(dpapi(encoded, protect = true)) else PlainPrefix + encoded
                properties.setProperty(key, stored)
                writeProperties(properties)
            }.onFailure {
                logger.warn("Credentials", "credential_save_failed", "Nie udało się zapisać poświadczenia")
            }
        }
    }

    override fun clear(key: String) = synchronized(lock) {
        val properties = readProperties()
        if (properties.remove(key) != null) writeProperties(properties)
    }

    private fun readProperties() = Properties().apply {
        if (Files.exists(file)) Files.newInputStream(file).use(::load)
    }

    private fun writeProperties(properties: Properties) {
        Files.createDirectories(file.parent)
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        Files.newOutputStream(
            temporary,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { properties.store(it, null) }
        runCatching {
            Files.setPosixFilePermissions(temporary, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }
        runCatching {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun dpapi(input: String, protect: Boolean): String? {
        val method = if (protect) "Protect" else "Unprotect"
        val script = """
            ${'$'}raw=[Console]::In.ReadToEnd().Trim()
            ${'$'}bytes=[Convert]::FromBase64String(${'$'}raw)
            ${'$'}result=[Security.Cryptography.ProtectedData]::$method(${'$'}bytes,${'$'}null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
            [Console]::Out.Write([Convert]::ToBase64String(${'$'}result))
        """.trimIndent()
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
            .redirectErrorStream(true)
            .start()
        process.outputStream.bufferedWriter().use { it.write(input) }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(process.waitFor() == 0) { "DPAPI operation failed" }
        return if (protect) output else decode(output)
    }

    private fun isWindows() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    private fun encode(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decode(value: String) = String(Base64.getDecoder().decode(value), Charsets.UTF_8)

    private companion object {
        const val DpapiPrefix = "dpapi:"
        const val PlainPrefix = "plain:"
    }
}
