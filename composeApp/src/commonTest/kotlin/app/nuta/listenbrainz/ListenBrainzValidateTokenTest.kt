package app.nuta.listenbrainz

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Parser odpowiedzi `/1/validate-token` — kształt z docs ListenBrainz. */
class ListenBrainzValidateTokenTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesValidTokenResponse() {
        val body = """{"code":200,"message":"Token valid.","valid":true,"user_name":"demo"}"""
        val valid = json.parseToJsonElement(body).jsonObject["valid"]?.jsonPrimitive?.booleanOrNull
        assertEquals(true, valid)
        assertTrue(valid == true)
    }

    @Test
    fun parsesInvalidTokenResponse() {
        val body = """{"code":200,"message":"Token invalid.","valid":false}"""
        val valid = json.parseToJsonElement(body).jsonObject["valid"]?.jsonPrimitive?.booleanOrNull
        assertEquals(false, valid)
        assertFalse(valid == true)
    }
}
