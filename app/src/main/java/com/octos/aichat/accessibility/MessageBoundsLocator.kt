package com.octos.aichat.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.octos.aichat.data.Speaker

/** A chat message's on-screen bounds (its bubble) and who sent it. */
data class MessageBounds(val rect: Rect, val speaker: Speaker)

/**
 * Some apps (e.g. Momo) draw their message text in a custom view that exposes
 * no text to accessibility, yet the node still carries the bubble's *bounds* and
 * its left/right placement. We use that geometry to crop the screenshot and OCR
 * only the real bubbles — which also drops suggestion panels and UI chrome that
 * whole-screen OCR would otherwise pick up.
 */
object MessageBoundsLocator {

    /** package → the view-id *name* of the message-text node. */
    private val MESSAGE_TEXT_ID: Map<String, String> = mapOf(
        "com.immomo.momo" to "message_tv_layouttextview",
    )

    fun hasRule(pkg: String): Boolean = pkg in MESSAGE_TEXT_ID

    /**
     * Collect message bounds for [pkg]. Author is taken from the bubble's own
     * horizontal placement (mine hugs the right, theirs the left) — reliable here
     * because the node is sized tightly to the bubble, not the full row width.
     */
    fun locate(root: AccessibilityNodeInfo?, screenWidth: Int, pkg: String): List<MessageBounds> {
        val idName = MESSAGE_TEXT_ID[pkg] ?: return emptyList()
        if (root == null || screenWidth <= 0) return emptyList()
        val out = ArrayList<MessageBounds>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (n.viewIdResourceName?.substringAfterLast('/') == idName) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (r.width() > 0 && r.height() > 0) {
                    val speaker = if (r.centerX() > screenWidth / 2) Speaker.ME else Speaker.OTHER
                    out.add(MessageBounds(r, speaker))
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return out
    }
}
