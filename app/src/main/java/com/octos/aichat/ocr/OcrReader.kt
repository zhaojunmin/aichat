package com.octos.aichat.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.octos.aichat.data.ChatMessage
import com.octos.aichat.data.Conversation
import com.octos.aichat.data.Speaker
import kotlin.coroutines.resume
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

    suspend fun readConversation(
        bitmap: Bitmap,
        screenWidth: Int,
        screenHeight: Int,
        pkg: String,
    ): Conversation {
        val text = recognize(bitmap) ?: return Conversation(pkg, emptyList())

        val top = screenHeight * 0.08f
        val bottom = screenHeight * 0.90f
        val blocks = text.textBlocks
            .mapNotNull { b -> b.boundingBox?.let { it to b.text.replace("\n", " ").trim() } }
            .filter { it.second.isNotEmpty() }
            .filter { screenHeight <= 0 || it.first.centerY().toFloat() in top..bottom }
            .sortedBy { it.first.top }

        val messages = ArrayList<ChatMessage>()
        var last: String? = null
        for ((box, content) in blocks) {
            if (!looksLikeMessage(content)) continue
            if (content == last) continue
            messages.add(ChatMessage(classify(bitmap, box, screenWidth), content))
            last = content
        }
        return Conversation(pkg, messages)
    }

    private suspend fun recognize(bitmap: Bitmap): Text? =
        suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }

    /**
     * Author from bubble background colour: WeChat's "me" bubble is green
     * (~#95EC69), the other party's is white/grey. We sample pixels inside the
     * text box — green hue (g noticeably above r and b) means mine. This is
     * immune to message length, unlike left/right position. Falls back to the
     * horizontal margin when no green is found (handles non-WeChat apps too).
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
                    val p = bitmap.getPixel(x, y)
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

    private fun looksLikeMessage(text: String): Boolean {
        if (text.length > 500) return false
        if (text.contains("正在输入")) return false
        if (text.contains("撤回了一条消息")) return false
        if (text.endsWith("加入了群聊") || text.endsWith("加入群聊")) return false
        if (text.matches(Regex("""^(上午|下午|昨天|今天)?\s*\d{1,2}:\d{2}$"""))) return false
        return true
    }
}
