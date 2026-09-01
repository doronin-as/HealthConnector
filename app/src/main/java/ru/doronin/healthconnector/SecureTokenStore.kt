package ru.doronin.healthconnector

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the Apps Script API token encrypted with an AES key that never leaves Android Keystore.
 *
 * Existing installs are migrated once from the legacy plaintext SharedPreferences key `token`.
 */
class SecureTokenStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    @Synchronized
    fun getToken(): String {
        val ciphertext = prefs.getString(KEY_CIPHERTEXT, null)
        val iv = prefs.getString(KEY_IV, null)
        if (!ciphertext.isNullOrBlank() && !iv.isNullOrBlank()) {
            return runCatching { decrypt(ciphertext, iv) }
                .getOrElse {
                    // A restored app backup or invalidated device key cannot decrypt the old secret.
                    // Never fall back to persisting plaintext: require the user to enter it again.
                    clearEncryptedToken()
                    ""
                }
        }

        val legacy = prefs.getString(LEGACY_TOKEN_KEY, "").orEmpty().trim()
        if (legacy.isNotBlank()) {
            setToken(legacy)
            return legacy
        }
        return ""
    }

    @Synchronized
    fun setToken(token: String) {
        val clean = token.trim()
        if (clean.isBlank()) {
            clear()
            return
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .remove(LEGACY_TOKEN_KEY)
            .apply()
    }

    @Synchronized
    fun clear() {
        clearEncryptedToken()
        prefs.edit().remove(LEGACY_TOKEN_KEY).apply()
    }

    private fun decrypt(ciphertext: String, iv: String): String {
        val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: error("Ключ шифрования отсутствует")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP))
        )
        val plain = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
        return plain.toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun clearEncryptedToken() {
        prefs.edit()
            .remove(KEY_CIPHERTEXT)
            .remove(KEY_IV)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "settings"
        private const val LEGACY_TOKEN_KEY = "token"
        private const val KEY_CIPHERTEXT = "token_ciphertext_v1"
        private const val KEY_IV = "token_iv_v1"
        private const val KEY_ALIAS = "healthconnector_api_token_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
