package com.octos.aichat.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.octos.aichat.diagnostics.DiagLog
import com.octos.aichat.diagnostics.ErrorCode
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Symmetric encryption of small secrets. Both directions throw on failure. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * String form of secrets kept in SharedPreferences (API keys).
 *
 * Stored value = [PREFIX] + Base64(cipher output). A value without the prefix is a
 * plaintext key from an older version: it is still returned (so nothing breaks on
 * upgrade) but flagged [Decoded.isLegacyPlaintext] so the caller re-saves it encrypted.
 */
class SecretCodec(private val cipher: SecretCipher) {

    data class Decoded(val value: String, val isLegacyPlaintext: Boolean = false)

    /** Throws if encryption fails; callers must not fall back to writing plaintext. */
    fun encode(secret: String): String =
        if (secret.isEmpty()) ""
        else PREFIX + Base64.getEncoder().encodeToString(cipher.encrypt(secret.toByteArray(Charsets.UTF_8)))

    /**
     * An encrypted value that can't be decrypted (e.g. the Keystore key was wiped)
     * decodes to "" — the user re-enters the key rather than the app crashing.
     */
    fun decode(stored: String?): Decoded {
        if (stored.isNullOrEmpty()) return Decoded("")
        if (!stored.startsWith(PREFIX)) return Decoded(stored, isLegacyPlaintext = true)
        val plain = runCatching {
            String(cipher.decrypt(Base64.getDecoder().decode(stored.removePrefix(PREFIX))), Charsets.UTF_8)
        }.getOrElse {
            DiagLog.record(ErrorCode.SET_802, cause = it)
            ""
        }
        return Decoded(plain)
    }

    companion object {
        const val PREFIX = "ks1:"
    }
}

/**
 * AES-256-GCM with a non-exportable key held in the Android Keystore. Output is
 * IV (12 bytes) followed by ciphertext + tag. The key never leaves secure
 * hardware/the keystore daemon, so a copied prefs file is useless on its own.
 */
object KeystoreSecretCipher : SecretCipher {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "settings_secrets_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "ciphertext too short" }
        val c = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        }
        return c.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }
}
