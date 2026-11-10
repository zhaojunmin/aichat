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
            messages.add(ChatMessage(classify(box, screenWidth), content))
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

    private fun classify(box: Rect, screenWidth: Int): Speaker {
        val leftMargin = box.left
        val rightMargin = screenWidth - box.right
        // Tie → lean to OTHER (safer: we won't wrongly drop it as my trailing msg).
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
