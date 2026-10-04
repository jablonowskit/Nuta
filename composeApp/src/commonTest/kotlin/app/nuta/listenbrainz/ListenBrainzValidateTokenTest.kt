package app.nuta.listenbrainz

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Kontrakt `/1/validate-token` i mapowanie na ostrzeżenie UI:
 * - `valid: true/false` → Boolean
 * - brak pola / błąd sieci → null (UI nie pokazuje „token nieprawidłowy”)
 */
class ListenBrainzValidateTokenTest {

    @Test
    fun parsesValidTokenResponse() {
        val body = """{"code":200,"message":"Token valid.","valid":true,"user_name":"demo"}"""
        assertEquals(true, parseValidateTokenValid(body))
    }

    @Test
    fun parsesInvalidTokenResponse() {
        val body = """{"code":200,"message":"Token invalid.","valid":false}"""
        assertEquals(false, parseValidateTokenValid(body))
    }

    @Test
    fun missingValidFieldIsNullNotFalse() {
        val body = """{"code":500,"message":"oops"}"""
        assertNull(parseValidateTokenValid(body))
    }

    @Test
    fun malformedBodyIsNullNotFalse() {
        assertNull(parseValidateTokenValid("not-json"))
    }

    @Test
    fun uiShowsInvalidOnlyWhenValidIsFalse() {
        // Symulacja: błąd sieci w validateSession → null; ostrzeżenie tylko przy false.
        val networkFailure: Boolean? = null
        val invalidToken: Boolean? = false
        assertEquals(false, networkFailure == false)
        assertEquals(true, invalidToken == false)
    }
}
