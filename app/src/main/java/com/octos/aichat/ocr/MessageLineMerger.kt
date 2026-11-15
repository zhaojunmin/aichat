package com.octos.aichat.ocr

import kotlin.math.abs

/** One OCR text block in screen pixels. Android-free so the merge rule is JVM-testable. */
internal data class OcrBlock(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val text: String,
    /** How many text lines OCR found in this block (≥ 1); used to estimate line height. */
    val lines: Int = 1,
    /**
     * Height of the block's first line — the type size of its text. Unlike
     * [lineHeight] it doesn't grow with the line spacing of a merged block.
     */
    val firstLineHeight: Int = (bottom - top) / lines.coerceAtLeast(1),
) {
    val lineHeight: Int get() = (bottom - top) / lines.coerceAtLeast(1)
}

/**
 * Whole-screen OCR sometimes returns each wrapped line of one chat bubble as its
 * own block, which would turn one message into several. This stitches blocks back
 * together when they are consecutive lines of the same bubble:
 *
 * - geometry: the lower block starts within about one line height below the upper
 *   one, with roughly the same left edge (bubble text is left-aligned). Gaps
 *   between two bubbles are much larger, and centred items (timestamps) don't
 *   share the edge;
 * - [sameMessage]: the caller's pixel check that both lines and the gap between
 *   them share one bubble background — so two tight bubbles, or a group sender's
 *   name above its bubble, never merge.
 */
internal object MessageLineMerger {

    /** @param blocks in any order; returned top to bottom. */
    fun merge(
        blocks: List<OcrBlock>,
        sameMessage: (upper: OcrBlock, lower: OcrBlock) -> Boolean,
    ): List<OcrBlock> {
        val out = ArrayList<OcrBlock>()
        for (b in blocks.sortedBy { it.top }) {
            val prev = out.lastOrNull()
            if (prev != null && adjacentLines(prev, b) && sameMessage(prev, b)) {
                out[out.size - 1] = OcrBlock(
                    left = minOf(prev.left, b.left),
                    top = prev.top,
                    right = maxOf(prev.right, b.right),
                    bottom = maxOf(prev.bottom, b.bottom),
                    text = joinLines(prev.text, b.text),
                    lines = prev.lines + b.lines,
                    firstLineHeight = prev.firstLineHeight,
                )
            } else {
                out.add(b)
            }
        }
        return out
    }

    internal fun adjacentLines(upper: OcrBlock, lower: OcrBlock): Boolean {
        val line = minOf(upper.lineHeight, lower.lineHeight)
        if (line <= 0) return false
        val gap = lower.top - upper.bottom
        // OCR box edges jitter by ~20 px between runs, so allow up to one line
        // height of horizontal drift; other-vs-mine bubbles and centred timestamps
        // are hundreds of pixels apart.
        return gap in -line / 2..line && abs(lower.left - upper.left) <= line
    }

    /**
     * Join two wrapped lines: Chinese wraps mid-sentence with no space, so when
     * either side of the break is CJK (incl. full-width punctuation) the lines are
     * glued; between Latin text ("hello," / "world") a space goes back in.
     */
    fun joinLines(upper: String, lower: String): String {
        val a = upper.trimEnd()
        val b = lower.trimStart()
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        return if (a.last().isCjk() || b.first().isCjk()) a + b else "$a $b"
    }

    private fun Char.isCjk(): Boolean =
        Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN ||
            code in 0x3000..0x303F || // CJK symbols & punctuation (。、「」)
            code in 0xFF00..0xFFEF // full-width forms (，！？：)
}
