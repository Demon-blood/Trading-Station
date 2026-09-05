package com.ksp.cryptobot.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Android-Keystore-backed secret store.
 *
 * M22 binds every AES-GCM ciphertext to its logical secret name with AAD so encrypted
 * blobs cannot be silently swapped between settings. Existing pre-M22 ciphertext is
 * migrated on first successful read.
 */
class SecureSettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("secure_bot_settings", Context.MODE_PRIVATE)
    private val keyAlias = "ksp_crypto_bot_master_key"

    fun saveSecret(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(aad(name))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))

        val committed = prefs.edit()
            .putString("${name}_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("${name}_data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putInt("${name}_format", FORMAT_AAD_V2)
            .commit()

        check(committed) {
            "Secure credential persistence failed for '$name'; refusing to report success."
        }
    }

    fun readSecret(name: String): String? {
        val ivRaw = prefs.getString("${name}_iv", null) ?: return null
        val dataRaw = prefs.getString("${name}_data", null) ?: return null
        val iv = Base64.decode(ivRaw, Base64.NO_WRAP)
        val data = Base64.decode(dataRaw, Base64.NO_WRAP)
        val format = prefs.getInt("${name}_format", FORMAT_LEGACY)

        if (format == FORMAT_AAD_V2) {
            return decrypt(name, iv, data, useAad = true)
        }

        // Backward-compatible migration: legacy CTS ciphertext had no associated data.
        // Only ciphertext explicitly lacking the v2 format marker is eligible.
        val legacy = decrypt(name, iv, data, useAad = false)
        saveSecret(name, legacy)
        return legacy
    }

    fun saveEncryptedString(name: String, value: String) = saveSecret(name, value)
    fun readEncryptedString(name: String): String? = readSecret(name)

    fun clearSecret(name: String) {
        val committed = prefs.edit()
            .remove("${name}_iv")
            .remove("${name}_data")
            .remove("${name}_format")
            .commit()
        check(committed) {
            "Secure credential clear failed for '$name'."
        }
    }

    private fun decrypt(
        name: String,
        iv: ByteArray,
        encrypted: ByteArray,
        useAad: Boolean
    ): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, iv)
        )
        if (useAad) cipher.updateAAD(aad(name))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun aad(name: String): ByteArray =
        "CTS_SECURE_V2:$name".toByteArray(Charsets.UTF_8)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.getKey(keyAlias, null)?.let { return it as SecretKey }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val FORMAT_LEGACY = 0
        private const val FORMAT_AAD_V2 = 2
    }
}
