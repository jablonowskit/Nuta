package app.nuta.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogRedactorTest {
    @Test
    fun masksTokensAndSignedParameters() {
        val redactor = LogRedactor()
        val fields = redactor.redact(
            mapOf(
                "authorization" to "Bearer top-secret",
                "url" to "https://example.test/audio?signature=abc123&x=1",
                "trackId" to "safe-id",
            ),
        )

        assertEquals("[REDACTED]", fields["authorization"])
        assertEquals("safe-id", fields["trackId"])
        assertFalse(fields.getValue("url").contains("abc123"))
    }

    @Test
    fun masksSpotifyWebSessionInTextAndJson() {
        val redactor = LogRedactor()
        val secret = "spotify-session-secret-123"
        val text = redactor.redactText(
            "Cookie: sp_dc=$secret; other=value JSON={\"accessToken\":\"token-value-456\"}",
        )

        assertFalse(text.contains(secret))
        assertFalse(text.contains("token-value-456"))
        assertTrue(text.contains("[REDACTED]"))
    }

    @Test
    fun masksSpotifySpecificFields() {
        val fields = LogRedactor().redact(
            mapOf("sp_dc" to "secret-cookie", "totpVersion" to "42", "status" to "ok"),
        )

        assertEquals("[REDACTED]", fields["sp_dc"])
        assertEquals("[REDACTED]", fields["totpVersion"])
        assertEquals("ok", fields["status"])
    }
}
