package app.nuta.core.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SecretValueTest {
    @Test
    fun neverRevealsValueThroughToString() {
        val raw = "highly-sensitive-value"
        val secret = SecretValue.of(raw)

        assertEquals("[REDACTED]", secret.toString())
        assertFalse("$secret".contains(raw))
        assertEquals(raw.length, secret.use(String::length))
    }

    @Test
    fun rejectsBlankValues() {
        assertFailsWith<IllegalArgumentException> { SecretValue.of("  ") }
    }
}
