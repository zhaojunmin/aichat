package com.octos.aichat.ai.embedding

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class VectorCodecTest {

    @Test fun roundTrip() {
        val v = floatArrayOf(0f, -1.5f, 3.25f, Float.MIN_VALUE, Float.MAX_VALUE)
        assertArrayEquals(v, VectorCodec.decode(VectorCodec.encode(v)), 0f)
    }

    @Test fun littleEndianFourBytesPerFloat() {
        val bytes = VectorCodec.encode(floatArrayOf(1f))
        // 1.0f = 0x3F800000, stored little-endian.
        assertArrayEquals(byteArrayOf(0, 0, 0x80.toByte(), 0x3F), bytes)
    }
}

/**
 * Runs against the real bge-small-zh `vocab.txt` shipped in assets (plain text, not
 * in LFS), so these ids are the ones the on-device encoder actually sees.
 */
class WordPieceTokenizerTest {

    private val vocabFile = File("src/main/assets/embed/vocab.txt")
    private val vocab: List<String> = vocabFile.readLines().map { it.trim() }
    private val tok = WordPieceTokenizer.fromFile(vocabFile.path)

    private fun id(t: String) = vocab.indexOf(t).also { require(it >= 0) { "token $t not in vocab" } }
    private fun pieces(text: String) = tok.encode(text).first.map { vocab[it] }

    @Test fun wrapsWithClsAndSep() {
        val (ids, mask) = tok.encode("你好")
        assertEquals(id("[CLS]"), ids.first())
        assertEquals(id("[SEP]"), ids.last())
        assertEquals(ids.size, mask.size)
        assertEquals(setOf(1), mask.toSet())
    }

    @Test fun cjkSplitPerCharAndPunctuationSeparated() {
        assertEquals(listOf("[CLS]", "我", "喜", "欢", "猫", "!", "[SEP]"), pieces("我喜欢猫!"))
    }

    @Test fun asciiLowercasedAndWordPieced() {
        val p = pieces("Hello")
        assertEquals("hello", p[1])
    }

    @Test fun unknownCharBecomesUnk() {
        // A private-use code point is never in the vocab.
        assertEquals(listOf("[CLS]", "[UNK]", "[SEP]"), pieces(""))
    }

    @Test fun truncatesToMaxLenKeepingSep() {
        val (ids, _) = tok.encode("好".repeat(500), maxLen = 16)
        assertEquals(16, ids.size)
        assertEquals(id("[SEP]"), ids.last())
    }

    @Test fun controlCharsDropped() {
        assertEquals(pieces("你好"), pieces("你\u0000​好"))
    }
}
