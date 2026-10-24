package com.phoneai.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM credential storage backed by a non-exportable Android Keystore key. */
internal object ApiKeyVault {
    private const val KEY_ALIAS = "orion_api_credentials_v1"
    private const val PREFS = "phoneai"
    private val lock = Any()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(byteArrayOf(iv.size.toByte()) + iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.isNotEmpty()) { "Empty credential ciphertext" }
        val ivSize = bytes[0].toInt() and 0xff
        require(ivSize in 12..16 && bytes.size > 1 + ivSize) { "Invalid credential ciphertext" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 1 + ivSize)))
        return String(cipher.doFinal(bytes.copyOfRange(1 + ivSize, bytes.size)), Charsets.UTF_8)
    }

    /** On first read, migrate plaintext only after ciphertext is committed successfully. */
    fun read(context: Context, encryptedName: String, legacyName: String): String = synchronized(lock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encrypted = prefs.getString(encryptedName, null)
        if (encrypted != null) return@synchronized try { decrypt(encrypted) } catch (_: Exception) { "" }
        val legacy = prefs.getString(legacyName, "") ?: ""
        if (legacy.isBlank()) return@synchronized ""
        try {
            if (!prefs.edit().putString(encryptedName, encrypt(legacy)).commit()) return@synchronized ""
            if (!prefs.edit().remove(legacyName).commit()) return@synchronized ""
            legacy
        } catch (_: Exception) {
            // Keep the legacy value intact for recovery if migration cannot be completed.
            ""
        }
    }

    /** Writes ciphertext first; plaintext is removed only after the encrypted write succeeds. */
    fun write(context: Context, encryptedName: String, legacyName: String, value: String): Boolean = synchronized(lock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val clean = value.trim()
        if (clean.isEmpty()) return@synchronized prefs.edit().remove(encryptedName).remove(legacyName).commit()
        try {
            if (!prefs.edit().putString(encryptedName, encrypt(clean)).commit()) return@synchronized false
            prefs.edit().remove(legacyName).commit()
        } catch (_: Exception) {
            false
        }
    }
}
