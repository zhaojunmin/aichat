package com.octos.aichat.ai.embedding

import java.io.File

/**
 * Minimal BERT/WordPiece tokenizer for bge-small-zh-v1.5 (`vocab.txt`, 21128 词):
 * basic tokenization(CJK 逐字、标点单独、小写)+ 贪心最长 WordPiece(`##` 续接),
 * 产出 `[CLS] … [SEP]` 的 input_ids 与全 1 的 attention_mask。纯 Kotlin,JNI 只收数字张量。
 *
 * 注:未做 strip-accents(中文无关,数据里罕见带重音的拉丁字母);与 HF tokenizer 的
 * 对齐需在集成验证阶段用真实语料回归(见 embedding-integration-design.md)。
 */
class WordPieceTokenizer private constructor(private val vocab: Map<String, Int>) {

    private val clsId = req("[CLS]")
    private val sepId = req("[SEP]")
    private val unkId = req("[UNK]")

    private fun req(t: String): Int = vocab[t] ?: error("vocab.txt missing token: $t")

    /** @return input_ids(含 CLS/SEP)与 attention_mask(全 1),截断到 [maxLen]。 */
    fun encode(text: String, maxLen: Int = 256): Pair<IntArray, IntArray> {
        val pieces = ArrayList<Int>()
        for (tok in basicTokenize(text)) wordPiece(tok, pieces)
        val bodyLen = minOf(pieces.size, maxLen - 2)
        val ids = IntArray(bodyLen + 2)
        ids[0] = clsId
        for (i in 0 until bodyLen) ids[i + 1] = pieces[i]
        ids[bodyLen + 1] = sepId
        return ids to IntArray(ids.size) { 1 }
    }

    private fun basicTokenize(text: String): List<String> {
        val sb = StringBuilder()
        for (ch in text) {
            val c = ch.code
            if (c == 0 || c == 0xFFFD || isControl(ch)) continue
            when {
                ch.isWhitespace() -> sb.append(' ')
                isCjk(c) -> sb.append(' ').append(ch).append(' ')
                isPunct(ch) -> sb.append(' ').append(ch).append(' ')
                else -> sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString().split(' ').filter { it.isNotEmpty() }
    }

    /** Greedy longest-match WordPiece; whole token → [UNK] if any piece is OOV. */
    private fun wordPiece(token: String, out: MutableList<Int>) {
        if (token.length > MAX_CHARS_PER_WORD) {
            out.add(unkId)
            return
        }
        val sub = ArrayList<Int>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var curId = -1
            while (start < end) {
                val piece = (if (start > 0) "##" else "") + token.substring(start, end)
                val v = vocab[piece]
                if (v != null) {
                    curId = v
                    break
                }
                end--
            }
            if (curId < 0) {
                out.add(unkId)
                return
            }
            sub.add(curId)
            start = end
        }
        out.addAll(sub)
    }

    private fun isControl(ch: Char): Boolean {
        if (ch == '\t' || ch == '\n' || ch == '\r') return false
        return ch.isISOControl() || Character.getType(ch).let {
            it == Character.CONTROL.toInt() || it == Character.FORMAT.toInt()
        }
    }

    private fun isPunct(ch: Char): Boolean {
        val c = ch.code
        if (c in 33..47 || c in 58..64 || c in 91..96 || c in 123..126) return true
        return when (Character.getType(ch)) {
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            -> true

            else -> false
        }
    }

    private fun isCjk(c: Int): Boolean =
        c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0x20000..0x2A6DF ||
            c in 0x2A700..0x2B73F || c in 0x2B740..0x2B81F || c in 0x2B820..0x2CEAF ||
            c in 0xF900..0xFAFF || c in 0x2F800..0x2FA1F

    companion object {
        private const val MAX_CHARS_PER_WORD = 100

        /** Load `vocab.txt` (one token per line; line index = id). */
        fun fromFile(vocabPath: String): WordPieceTokenizer {
            val map = HashMap<String, Int>(24000)
            File(vocabPath).bufferedReader().useLines { lines ->
                lines.forEachIndexed { i, line -> map[line.trim()] = i }
            }
            return WordPieceTokenizer(map)
        }
    }
}
