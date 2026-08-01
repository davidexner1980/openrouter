package com.david.openassistant.data.security

import android.content.Context
import androidx.core.content.edit
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ApiKeyStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(apiKey: String) {
        require(apiKey.isNotBlank()) { "API key cannot be blank." }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(apiKey.toByteArray(StandardCharsets.UTF_8))

        val saved = preferences.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()
        check(saved) { "The encrypted API key could not be written to app storage." }
    }

    fun load(): String? {
        val encryptedBase64 = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        val ivBase64 = preferences.getString(KEY_IV, null) ?: return null

        return runCatching {
            val encrypted = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val secretKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
                ?: error("Encryption key is unavailable.")

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    fun hasEncryptedCredential(): Boolean =
        preferences.contains(KEY_CIPHERTEXT) && preferences.contains(KEY_IV)

    fun hasStoredKey(): Boolean = hasEncryptedCredential() && load() != null

    fun clear() {
        // Credential deletion is a security boundary: prove the encrypted
        // preferences were durably cleared before deleting their keystore key.
        check(
            runCatching {
                preferences.edit(commit = true) { clear() }
                true
            }.getOrDefault(false),
        ) {
            "The encrypted API key could not be removed from app storage."
        }
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val specification = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()

        generator.init(specification)
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "openassistant_secure_credentials"
        const val KEY_CIPHERTEXT = "openrouter_key_ciphertext"
        const val KEY_IV = "openrouter_key_iv"
        const val KEY_ALIAS = "openassistant_openrouter_key_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
