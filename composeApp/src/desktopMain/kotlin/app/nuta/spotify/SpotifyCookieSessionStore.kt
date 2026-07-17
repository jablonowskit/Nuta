package app.nuta.spotify

import app.nuta.core.logging.MemoryLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import org.cef.callback.CefCookieVisitor
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Date

class SpotifyCookieSessionStore(private val logger: MemoryLogger) {
    private val directory = Path.of(System.getenv("NUTA_SESSION_DIR") ?: "/home/nuta/.local/share/nuta/spotify-session")
    private val dataFile = directory.resolve("cookies.test.json")

    suspend fun restore(): Int {
        if (!Files.exists(dataFile)) {
            logger.info("SpotifySession", "cookie_restore_skipped", "Brak własnego magazynu cookies Spotify")
            return 0
        }
        return runCatching {
            val root = Json.parseToJsonElement(Files.readString(dataFile)).jsonArray
            val manager = CefCookieManager.getGlobalManager()
            var restored = 0
            root.forEach { element ->
                val item = element.jsonObject
                val domain = item.string("domain")
                val path = item.string("path").ifBlank { "/" }
                val expiresAt = item.long("expiresAt")
                val cookie = CefCookie(
                    item.string("name"), item.string("value"), domain, path,
                    item.boolean("secure"), item.boolean("httpOnly"), Date(), Date(),
                    expiresAt > 0, expiresAt.takeIf { it > 0 }?.let(::Date),
                )
                val host = domain.removePrefix(".")
                if (host.endsWith("spotify.com") && manager.setCookie("https://$host$path", cookie)) restored++
            }
            logger.info("SpotifySession", "cookie_restore_completed", "Odtworzono cookies Spotify", fields = mapOf("count" to restored.toString()))
            restored
        }.onFailure { error ->
            logger.error("SpotifySession", "cookie_restore_failed", "Nie udało się odtworzyć cookies Spotify", throwable = error)
        }.getOrDefault(0)
    }

    suspend fun save(): Int {
        val cookies = collectSpotifyCookies()
        require(cookies.isNotEmpty()) { "JCEF nie zwrócił cookies Spotify" }
        Files.createDirectories(directory)
        val json = JsonArray(cookies.map { cookie ->
            buildJsonObject {
                put("name", cookie.name); put("value", cookie.value); put("domain", cookie.domain); put("path", cookie.path)
                put("secure", cookie.secure); put("httpOnly", cookie.httponly)
                put("expiresAt", if (cookie.hasExpires) cookie.expires?.time ?: 0 else 0)
            }
        }).toString()
        val temporary = directory.resolve("cookies.test.json.tmp")
        Files.writeString(temporary, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        setOwnerOnly(temporary)
        Files.move(temporary, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        logger.info("SpotifySession", "cookie_store_saved", "Zapisano testowy magazyn cookies Spotify", fields = mapOf("count" to cookies.size.toString(), "encrypted" to "false"))
        return cookies.size
    }

    private suspend fun collectSpotifyCookies(): List<CefCookie> {
        val result = CompletableDeferred<List<CefCookie>>()
        val cookies = mutableListOf<CefCookie>()
        val accepted = CefCookieManager.getGlobalManager().visitAllCookies(
            CefCookieVisitor { cookie, count, total, _ ->
                if (cookie.domain.removePrefix(".").endsWith("spotify.com")) cookies += cookie
                if (count + 1 >= total) result.complete(cookies.toList())
                true
            },
        )
        require(accepted) { "JCEF odrzucił odczyt cookies" }
        return withTimeoutOrNull(5_000) { result.await() } ?: cookies.toList()
    }

    private fun setOwnerOnly(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(path, setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            ))
        }
    }

    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content
    private fun JsonObject.long(name: String) = getValue(name).jsonPrimitive.long
    private fun JsonObject.boolean(name: String) = getValue(name).jsonPrimitive.boolean
}
