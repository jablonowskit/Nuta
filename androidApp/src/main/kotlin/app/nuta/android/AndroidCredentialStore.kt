package app.nuta.android

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import app.nuta.settings.CredentialStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Sekrety zaszyfrowane AES-GCM kluczem, który nigdy nie opuszcza Android Keystore. */
class AndroidCredentialStore(
    private val preferences: SharedPreferences,
) : CredentialStore {
    /** True, gdy w prefs był blob, ale deszyfrowanie się nie udało (Keystore / uszkodzone dane). */
    @Volatile
    var lastDecryptFailed: Boolean = false
        private set

    override fun load(key: String): String? {
        lastDecryptFailed = false
        val encoded = preferences.getString(key, null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > IvSize)
            val cipher = Cipher.getInstance(Transformation)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TagBits, payload, 0, IvSize))
            String(cipher.doFinal(payload, IvSize, payload.size - IvSize), Charsets.UTF_8)
        }.getOrElse { error ->
            lastDecryptFailed = true
            Log.w("NutaCredentials", "Nie udało się odszyfrować sekretu ($key)", error)
            null
        }
    }

    override fun save(key: String, value: String) {
        if (value.isBlank()) {
            clear(key)
            return
        }
        lastDecryptFailed = false
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + encrypted
        preferences.edit().putString(key, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
    }

    override fun clear(key: String) {
        lastDecryptFailed = false
        preferences.edit().remove(key).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(Keystore).apply { load(null) }
        (keyStore.getKey(Alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, Keystore).run {
            init(
                KeyGenParameterSpec.Builder(
                    Alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val Alias = "nuta.credentials.v1"
        const val Keystore = "AndroidKeyStore"
        const val Transformation = "AES/GCM/NoPadding"
        const val IvSize = 12
        const val TagBits = 128
    }
}
