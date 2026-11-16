package com.octos.aichat.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.core.graphics.get
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.octos.aichat.accessibility.MessageBounds
import com.octos.aichat.data.ChatMessage
import com.octos.aichat.data.Conversation
import com.octos.aichat.data.Speaker
import com.octos.aichat.diagnostics.DiagLog
import com.octos.aichat.diagnostics.ErrorCode
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Screenshot → conversation, using on-device ML Kit Chinese OCR. Used as a
 * fallback for apps whose chat content the accessibility node tree can't read.
 *
 * The author of each bubble is guessed from its horizontal placement: a chat
 * bubble hugs the side of its sender (mine on the right, theirs on the left),
 * so whichever margin is smaller tells us who sent it.
 */
object OcrReader {

    private val recognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    /**
     * Geometry-guided read: OCR the whole screenshot once, then keep only the text
     * blocks that fall inside a known bubble rectangle, tagging each with that
     * bubble's speaker. Everything outside the bubbles (suggestion panels, profile
     * cards, input bar, nav) is dropped for free, and the author is exact.
     */
    suspend fun readConversation(
        bitmap: Bitmap,
        messageBounds: List<MessageBounds>,
        pkg: String,
    ): Conversation {
        if (messageBounds.isEmpty()) return Conversation(pkg, emptyList())
        val text = recognize(bitmap, pkg) ?: return Conversation(pkg, emptyList())

        val blocks = text.textBlocks
            .mapNotNull { b -> b.boundingBox?.let { it to b.text.replace("\n", " ").trim() } }
            .filter { it.second.isNotEmpty() }

        // Each bubble collects the text blocks whose centre sits inside it.
        val ordered = messageBounds.sortedBy { it.rect.top }
        val messages = ArrayList<ChatMessage>()
        var last: String? = null
        for (bounds in ordered) {
            val content = blocks
                .filter { bounds.rect.contains(it.first.centerX(), it.first.centerY()) }
                .sortedBy { it.first.top }
                .joinToString(" ") { it.second }
                .trim()
            if (content.isEmpty() || content == last) continue
            if (!OcrMessageFilter.looksLikeMessage(content)) continue
            messages.add(ChatMessage(bounds.speaker, content))
            last = content
        }
        return Conversation(pkg, messages)
    }

    suspend fun readConversation(
        bitmap: Bitmap,
        screenWidth: Int,
        screenHeight: Int,
        pkg: String,
    ): Conversation {
        val text = recognize(bitmap, pkg) ?: return Conversation(pkg, emptyList())

        // The screenshot is the full physical display, so its own height is the
        // authoritative reference. The caller's screenHeight (displayMetrics)
        // excludes the nav bar and is smaller than the bitmap — using it here
        // shifted every band upward and clipped the newest (bottom) messages.
        val h = if (bitmap.height > 0) bitmap.height else screenHeight

        val all = text.textBlocks
            .mapNotNull { b ->
                val r = b.boundingBox ?: return@mapNotNull null
                val lines = b.lines.map { it.text }.ifEmpty { listOf(b.text) }
                val joined = lines.reduce(MessageLineMerger::joinLines).trim()
                OcrBlock(r.left, r.top, r.right, r.bottom, joined, lines.size)
            }
            .filter { it.text.isNotEmpty() }
            .sortedBy { it.top }

        // Upper bound: the first full date-time stamp marks where the chat starts,
        // so the profile card above it is excluded. Fall back to a fixed band.
        val firstStamp = all.indexOfFirst { OcrMessageFilter.DATE_RE.containsMatchIn(it.text) }
        val minY = if (firstStamp >= 0) {
            all[firstStamp].top.toFloat()
        } else {
            h * 0.08f
        }
        // Lower bound: only exclude the gesture nav bar at the very bottom. The
        // input box text ("请输入消息" / "发送") is dropped by looksLikeMessage,
        // so we can keep the band low enough to retain the latest messages that
        // sit just above the input bar.
        val maxY = h * 0.94f

        // Drop timestamps / system lines first, so they can never be glued onto a
        // neighbouring block (e.g. OCR noise from the wallpaper right above a time).
        val candidates = all.filter {
            val centerY = ((it.top + it.bottom) / 2).toFloat()
            (h <= 0 || centerY in minY..maxY) && OcrMessageFilter.looksLikeMessage(it.text)
        }
        // One bubble's wrapped lines can come back as separate blocks; stitch them.
        val merged = MessageLineMerger.merge(candidates) { upper, lower ->
            gapInsideMessage(bitmap, upper, lower)
        }
        // Group chats: drop the sender name printed above each bubble.
        val named = SenderNameFilter.drop(merged) { name, below ->
            val a = paddingColor(bitmap, name, name.lineHeight)
            val b = paddingColor(bitmap, below, name.lineHeight)
            // Unreadable pixels → treat as same background, i.e. keep the block.
            // Both samples are flat fills (no glyph edges), so the tolerance is tight:
            // a pale wallpaper (#E7F0F9 measured) is only 45 off a white bubble.
            a == null || b == null || similar(a, b, FLAT_TOLERANCE)
        }
        // Quoted text under a reply (and other small secondary text) is not a message.
        val blocks = QuoteFilter.drop(named)

        val messages = ArrayList<ChatMessage>()
        var last: String? = null
        for (b in blocks) {
            val content = b.text
            // e.g. merged text now over the length cap
            if (!OcrMessageFilter.looksLikeMessage(content)) continue
            if (content == last) continue
            val speaker = classify(bitmap, Rect(b.left, b.top, b.right, b.bottom), screenWidth)
            messages.add(ChatMessage(speaker, content))
            last = content
        }
        return Conversation(pkg, messages)
    }

    /**
     * True when the strip between two OCR lines is one bubble's own background,
     * i.e. both lines sit inside the same bubble. Reference colours = the bubble
     * padding just left of each line; they must match each other, and every sample
     * across the gap must match them. Two separate bubbles show chat background /
     * wallpaper in the gap; a group-chat sender name sits on the wallpaper while
     * the line below it sits in a bubble, so their references differ.
     */
    private fun gapInsideMessage(bitmap: Bitmap, upper: OcrBlock, lower: OcrBlock): Boolean {
        val line = minOf(upper.lineHeight, lower.lineHeight)
        val inset = insetFor(line)
        val upperRef = paddingColor(bitmap, upper, line) ?: return false
        val lowerRef = paddingColor(bitmap, lower, line) ?: return false
        if (!similar(upperRef, lowerRef)) return false

        val gap = lower.top - upper.bottom
        if (gap <= 2) return true // lines touch: nothing in between to test
        val y = upper.bottom + gap / 2
        val from = minOf(upper.left, lower.left) - inset
        val to = minOf(upper.right, lower.right)
        if (to <= from) return false
        val steps = 8
        for (i in 0..steps) {
            val px = pixelOrNull(bitmap, from + (to - from) * i / steps, y) ?: return false
            if (!similar(px, upperRef)) return false
        }
        return true
    }

    /**
     * Colour just left of a block's text, at its vertical middle: the bubble's own
     * padding when the text is in a bubble, else the chat background / wallpaper.
     */
    private fun paddingColor(bitmap: Bitmap, block: OcrBlock, line: Int): Int? =
        pixelOrNull(bitmap, block.left - insetFor(line), (block.top + block.bottom) / 2)

    private fun insetFor(line: Int) = (line / 5).coerceAtLeast(2)

    private fun pixelOrNull(bitmap: Bitmap, x: Int, y: Int): Int? =
        if (x in 0 until bitmap.width && y in 0 until bitmap.height) bitmap[x, y] else null

    /** Colour distance allowed when sampling near text (anti-aliased glyph edges). */
    private const val EDGE_TOLERANCE = 48

    /** Colour distance allowed between two flat fills, e.g. bubble padding vs. background. */
    private const val FLAT_TOLERANCE = 12

    private fun similar(a: Int, b: Int, tolerance: Int = EDGE_TOLERANCE): Boolean {
        val d = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
            abs((a and 0xFF) - (b and 0xFF))
        return d <= tolerance
    }

    private suspend fun recognize(bitmap: Bitmap, pkg: String): Text? =
        suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener {
                    DiagLog.record(ErrorCode.OCR_401, cause = it, pkg = pkg)
                    if (cont.isActive) cont.resume(null)
                }
        }

    /**
     * Author from bubble background colour: WeChat's "me" bubble is green
     * (~#95EC69), the other party's is white/grey. We sample pixels inside the
     * text box — a green hue (g noticeably above r and b) means mine. This is
     * immune to message length, unlike left/right position. Falls back to the
     * horizontal margin when no green is found.
     */
    private fun classify(bitmap: Bitmap, box: Rect, screenWidth: Int): Speaker {
        val w = bitmap.width
        val h = bitmap.height
        val stepX = (box.width() / 6).coerceAtLeast(1)
        val stepY = (box.height() / 4).coerceAtLeast(1)
        var green = 0
        var total = 0
        var y = box.top
        while (y < box.bottom) {
            var x = box.left
            while (x < box.right) {
                if (x in 0 until w && y in 0 until h) {
                    val p = bitmap[x, y]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    // Green hue across light & dark WeChat themes.
                    if (g > 60 && g > r + 18 && g > b + 18) green++
                    total++
                }
                x += stepX
            }
            y += stepY
        }
        if (total > 0 && green.toFloat() / total > 0.08f) return Speaker.ME

        // No green bubble detected → fall back to horizontal placement.
        val leftMargin = box.left
        val rightMargin = screenWidth - box.right
        return if (rightMargin < leftMargin) Speaker.ME else Speaker.OTHER
    }
}
