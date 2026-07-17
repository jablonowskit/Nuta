package app.nuta.spotify

import app.nuta.core.logging.MemoryLogger
import app.nuta.core.security.SecretValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class SpotifyTestTokenStore(private val logger: MemoryLogger) {
    private val file = Path.of(System.getenv("NUTA_SESSION_DIR") ?: "/home/nuta/.local/share/nuta/spotify-session")
        .resolve("token.test.json")

    fun load(): SpotifyWebToken? = runCatching {
        if (!Files.exists(file)) return null
        val root = Json.parseToJsonElement(Files.readString(file)).jsonObject
        val expiresAt = root.getValue("expiresAt").jsonPrimitive.content.toLong()
        if (expiresAt <= System.currentTimeMillis() + 60_000) {
            logger.info("SpotifySession", "test_token_expired", "Testowy token Spotify wygasł")
            return null
        }
        val token = SpotifyWebToken(SecretValue.of(root.getValue("accessToken").jsonPrimitive.content), expiresAt)
        logger.info("SpotifySession", "test_token_restored", "Odtworzono testowy token Spotify", fields = mapOf("expiresAt" to expiresAt.toString()))
        token
    }.onFailure { error ->
        logger.error("SpotifySession", "test_token_restore_failed", "Nie udało się odtworzyć testowego tokenu Spotify", throwable = error)
    }.getOrNull()

    fun save(token: SpotifyWebToken) {
        Files.createDirectories(file.parent)
        val payload = buildJsonObject {
            token.value.use { put("accessToken", it) }
            put("expiresAt", token.expiresAtMs)
        }.toString()
        Files.writeString(file, payload, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        runCatching {
            Files.setPosixFilePermissions(file, setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            ))
        }
        logger.info("SpotifySession", "test_token_saved", "Zapisano testowy token Spotify", fields = mapOf("expiresAt" to token.expiresAtMs.toString()))
    }
}
