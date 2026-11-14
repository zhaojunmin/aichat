package com.octos.aichat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretCodecTest {

    /** Same AES-256-GCM layout as KeystoreSecretCipher (IV ‖ ct+tag), with a JVM key. */
    private class JvmGcmCipher(private val key: SecretKey = newKey()) : SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            return c.iv + c.doFinal(plain)
        }
        override fun decrypt(blob: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12)) }
            return c.doFinal(blob, 12, blob.size - 12)
        }
        companion object {
            fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
    }

    private val codec = SecretCodec(JvmGcmCipher())

    @Test fun roundTrip_andNoPlaintextInStoredForm() {
        val key = "sk-1234567890abcdef中文"
        val stored = codec.encode(key)
        assertTrue(stored.startsWith(SecretCodec.PREFIX))
        assertFalse(stored.contains("sk-1234"))
        assertEquals(SecretCodec.Decoded(key), codec.decode(stored))
    }

    @Test fun freshIvEachTime() {
        assertNotEquals(codec.encode("same"), codec.encode("same"))
    }

    @Test fun emptyStaysEmpty() {
        assertEquals("", codec.encode(""))
        assertEquals(SecretCodec.Decoded(""), codec.decode(""))
        assertEquals(SecretCodec.Decoded(""), codec.decode(null))
    }

    /** Upgrade from a version that stored keys in plaintext: still readable, flagged for re-save. */
    @Test fun legacyPlaintextIsReturnedAndFlagged() {
        assertEquals(SecretCodec.Decoded("sk-old", isLegacyPlaintext = true), codec.decode("sk-old"))
    }

    /** Keystore key wiped / different device: decode to "" (user re-enters) instead of crashing. */
    @Test fun undecryptableBecomesEmpty() {
        val stored = codec.encode("sk-x")
        val otherKey = SecretCodec(JvmGcmCipher())
        assertEquals(SecretCodec.Decoded(""), otherKey.decode(stored))
        assertEquals(SecretCodec.Decoded(""), codec.decode(SecretCodec.PREFIX + "not base64!!"))
        assertEquals(SecretCodec.Decoded(""), codec.decode(SecretCodec.PREFIX))
    }

    @Test fun tamperedCiphertextRejected() {
        val stored = codec.encode("sk-x")
        val flipped = stored.dropLast(2) + (if (stored[stored.length - 2] == 'A') "B" else "A") + stored.last()
        assertEquals("", codec.decode(flipped).value)
    }

    /** Encryption failure must surface (caller then refuses to persist) — never a silent plaintext write. */
    @Test fun encryptFailurePropagates() {
        val broken = SecretCodec(object : SecretCipher {
            override fun encrypt(plain: ByteArray): ByteArray = throw IllegalStateException("keystore down")
            override fun decrypt(blob: ByteArray): ByteArray = throw IllegalStateException()
        })
        try {
            broken.encode("sk-x")
            fail("expected failure")
        } catch (_: IllegalStateException) {
        }
    }
}
