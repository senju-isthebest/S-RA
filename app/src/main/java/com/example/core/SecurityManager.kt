package com.example.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.example.data.ai.Base64Codec
import com.example.data.ai.SecretRedactor
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "SecurityManager"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "sora_local_secrets_key_v1"
private const val ENC_PREFIX = "enc_v1:"
private const val GCM_TAG_BITS = 128
private const val IV_SIZE_BYTES = 12

/**
 * Hardware/AndroidKeyStore-backed AES-256-GCM box for encrypting sensitive local user secrets
 * (e.g., optional custom Gemini API keys) in DataStore preferences.
 */
object EncryptedSecretBox {

    @Volatile
    private var jvmFallbackKey: SecretKey? = null

    fun encrypt(plaintext: String): String {
        val clean = plaintext.trim()
        if (clean.isEmpty()) return ""
        if (clean.startsWith(ENC_PREFIX)) return clean
        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv ?: ByteArray(IV_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
            val ciphertext = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
            ENC_PREFIX + Base64Codec.encode(combined)
        } catch (e: Exception) {
            Log.e(TAG, "Secret encryption failed cleanly; returning unpersisted empty string", e)
            ""
        }
    }

    fun decrypt(stored: String): String {
        val trimmed = stored.trim()
        if (trimmed.isEmpty()) return ""
        if (!trimmed.startsWith(ENC_PREFIX)) {
            // Backward compatibility: legacy plaintext key before encryption migration
            return trimmed
        }
        return try {
            val payload = trimmed.removePrefix(ENC_PREFIX)
            val combined = decodeBase64(payload)
            if (combined.size <= IV_SIZE_BYTES) return ""
            val iv = combined.copyOfRange(0, IV_SIZE_BYTES)
            val ciphertext = combined.copyOfRange(IV_SIZE_BYTES, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decrypt local secret (key rotated or corrupted)", e)
            ""
        }
    }

    fun isEncryptedPayload(stored: String): Boolean = stored.trim().startsWith(ENC_PREFIX)

    private fun getOrCreateKey(): SecretKey {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) return existing
            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            keyGen.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            keyGen.generateKey()
        } catch (_: Exception) {
            // JVM / Robolectric fallback when AndroidKeyStore provider is unavailable
            jvmFallbackKey ?: synchronized(this) {
                jvmFallbackKey ?: run {
                    val bytes = ByteArray(32)
                    SecureRandom().nextBytes(bytes)
                    SecretKeySpec(bytes, "AES").also { jvmFallbackKey = it }
                }
            }
        }
    }

    private fun decodeBase64(encoded: String): ByteArray {
        return try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (_: Throwable) {
            java.util.Base64.getDecoder().decode(encoded)
        }
    }
}

object InputValidator {
    private val CONTROL_CHARS = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
    private val INVALID_FILENAME_CHARS = Regex("[\\\\/:*?\"<>|\\x00-\\x1F]")

    /**
     * Prevents directory traversal (`..`, `/`, `\`) and control-character filenames.
     */
    fun sanitizeFileName(raw: String, fallback: String = "study_material"): String {
        val base = raw
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace("..", "_")
            .replace(INVALID_FILENAME_CHARS, "_")
            .trim()
            .trim('.')
        return base.take(120).ifBlank { fallback }
    }

    fun sanitizeText(raw: String, maxLength: Int): String {
        return raw.replace(CONTROL_CHARS, "").trim().take(maxLength.coerceAtLeast(1))
    }

    fun maskUid(uid: String): String {
        val clean = uid.trim()
        if (clean.length <= 8) return if (clean.isEmpty()) "-" else "••••${clean.takeLast(2)}"
        return "${clean.take(4)}••••${clean.takeLast(4)}"
    }

    fun redactSecrets(text: String, secrets: List<String>): String =
        SecretRedactor.redact(text, secrets)
}
