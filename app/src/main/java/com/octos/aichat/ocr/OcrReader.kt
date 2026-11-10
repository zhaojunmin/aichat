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

        val all = text.textBlocks
            .mapNotNull { b -> b.boundingBox?.let { it to b.text.replace("\n", " ").trim() } }
            .filter { it.second.isNotEmpty() }
            .sortedBy { it.first.top }

        // Upper bound: the first full date-time stamp marks where the chat starts,
        // so the profile card above it is excluded. Fall back to a fixed band.
        val firstStamp = all.indexOfFirst { DATE_RE.containsMatchIn(it.second) }
        val minY = if (firstStamp >= 0) all[firstStamp].first.top.toFloat()
        else screenHeight * 0.08f
        // Lower bound: drop the input box (incl. any draft text), suggestion
        // chips and nav icons — they occupy the bottom ~18% of the screen.
        val maxY = screenHeight * 0.82f

        val blocks = all.filter { screenHeight <= 0 || it.first.centerY().toFloat() in minY..maxY }

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
     * Author from bubble background colour. "Me" bubbles are tinted (WeChat
     * green ~#95EC69, Hertz cyan ~#64C8E1), the other party's is white/grey.
     * The shared signal is green well above red (g - r large) — green and cyan
     * both satisfy it, while white/grey (g≈r), pink badges (g<r) and the pale
     * background (g-r≈25) do not. Immune to message length, unlike position.
     * Falls back to the horizontal margin when no tint is found.
     */
    private fun classify(bitmap: Bitmap, box: Rect, screenWidth: Int): Speaker {
        val w = bitmap.width
        val h = bitmap.height
        val stepX = (box.width() / 6).coerceAtLeast(1)
        val stepY = (box.height() / 4).coerceAtLeast(1)
        var tinted = 0
        var total = 0
        var y = box.top
        while (y < box.bottom) {
            var x = box.left
            while (x < box.right) {
                if (x in 0 until w && y in 0 until h) {
                    val p = bitmap.getPixel(x, y)
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    // Green/cyan "me" bubble: bright and green clearly above red.
                    if (g > 150 && g - r > 35) tinted++
                    total++
                }
                x += stepX
            }
            y += stepY
        }
        if (total > 0 && tinted.toFloat() / total > 0.08f) return Speaker.ME

        // No tinted bubble detected → fall back to horizontal placement.
        val leftMargin = box.left
        val rightMargin = screenWidth - box.right
        return if (rightMargin < leftMargin) Speaker.ME else Speaker.OTHER
    }

    private val DATE_RE = Regex("""\d{4}-\d{1,2}-\d{1,2}""")

    // UI chrome that OCR picks up but isn't chat content.
    private val UI_EXACT = setOf(
        "调皮", "亲亲", "以图换图", "分享此刻", "催一下", "请输入消息",
        "发送", "关注", "查看主页", "查看对方是否已读", "哈哈", "略略略",
        "嘿嘿", "么么", "在线",
    )
    private val UI_CONTAINS = listOf(
        "共鸣度", "查看对方", "Ta的标签", "还未上传声音", "可提现", "成长值", "请输入",
        "结成关系", "亲密关系",
    )

    private fun looksLikeMessage(text: String): Boolean {
        if (text.length > 500) return false
        if (text.contains("正在输入")) return false
        if (text.contains("撤回了一条消息")) return false
        if (text.endsWith("加入了群聊") || text.endsWith("加入群聊")) return false
        // Date-time separators (e.g. "2026-11-10 09:15:31").
        if (DATE_RE.containsMatchIn(text)) return false
        // Short time stamps like "12:34" / "下午 3:51".
        if (text.matches(Regex("""^(上午|下午|昨天|今天)?\s*\d{1,2}:\d{2}$"""))) return false
        // Bare digit badges (unread counts / level badges like "4", "100").
        if (text.matches(Regex("""^\d{1,3}$"""))) return false
        // App UI chrome (suggestion chips, input hint, profile card, nav).
        if (text in UI_EXACT) return false
        if (UI_CONTAINS.any { text.contains(it) }) return false
        // Percentage badges like "82%共鸣度".
        if (text.matches(Regex("""^\d{1,3}%.*"""))) return false
        return true
    }
}
