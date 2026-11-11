package com.octos.aichat.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.octos.aichat.data.Speaker

/** A located chat bubble: its on-screen rectangle and who sent it. */
data class BubbleBox(val rect: Rect, val speaker: Speaker)

/**
 * Some apps (e.g. Momo) draw their message text in a custom view that exposes
 * no text to accessibility, yet the node still carries the bubble's *bounds* and
 * its left/right placement. We use that geometry to crop the screenshot and OCR
 * only the real bubbles — which also drops suggestion panels and UI chrome that
 * whole-screen OCR would otherwise pick up.
 */
object BubbleLocator {

    /** package → the view-id *name* of the message-text node. */
    private val BUBBLE_ID: Map<String, String> = mapOf(
        "com.immomo.momo" to "message_tv_layouttextview",
    )

    fun hasRule(pkg: String): Boolean = pkg in BUBBLE_ID

    /**
     * Collect bubble rectangles for [pkg]. Author is taken from the bubble's own
     * horizontal placement (mine hugs the right, theirs the left) — reliable here
     * because the node is sized tightly to the bubble, not the full row width.
     */
    fun locate(root: AccessibilityNodeInfo?, screenWidth: Int, pkg: String): List<BubbleBox> {
        val idName = BUBBLE_ID[pkg] ?: return emptyList()
        if (root == null || screenWidth <= 0) return emptyList()
        val out = ArrayList<BubbleBox>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (n.viewIdResourceName?.substringAfterLast('/') == idName) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (r.width() > 0 && r.height() > 0) {
                    val speaker = if (r.centerX() > screenWidth / 2) Speaker.ME else Speaker.OTHER
                    out.add(BubbleBox(r, speaker))
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return out
    }
}
