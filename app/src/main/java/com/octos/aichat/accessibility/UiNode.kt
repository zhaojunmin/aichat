package com.octos.aichat.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Screen-space rectangle as plain data (android.graphics.Rect is a no-op stub on the JVM). */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/**
 * An immutable snapshot of one accessibility node: just the fields the parsers
 * read. Parsing works on this plain tree instead of [AccessibilityNodeInfo], so
 * the parsing rules can be unit-tested on the JVM against recorded screen dumps.
 *
 * [viewId] is the full resource name (`pkg:id/name`); [idName] is the part after
 * `id/`, which is what per-app rules match on.
 */
data class UiNode(
    val viewId: String?,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val bounds: Bounds,
    val children: List<UiNode> = emptyList(),
) {
    val idName: String? get() = viewId?.substringAfterLast('/')

    /** Visible text of the node: its text, else its content description; trimmed, null if blank. */
    val label: String? get() = (text ?: contentDescription)?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Snapshot this node's subtree. Each child fetch is a binder call, so the walk is
 * capped at [maxNodes]; a chat screen has a few hundred nodes at most.
 */
fun AccessibilityNodeInfo.toUiNode(maxNodes: Int = 3000): UiNode {
    var budget = maxNodes
    val rect = Rect()
    fun snapshot(n: AccessibilityNodeInfo): UiNode {
        budget--
        // Read bounds before recursing: [rect] is reused by the children.
        n.getBoundsInScreen(rect)
        val bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom)
        val kids = ArrayList<UiNode>(n.childCount)
        for (i in 0 until n.childCount) {
            if (budget <= 0) break
            n.getChild(i)?.let { kids.add(snapshot(it)) }
        }
        return UiNode(
            viewId = n.viewIdResourceName,
            className = n.className?.toString(),
            text = n.text?.toString(),
            contentDescription = n.contentDescription?.toString(),
            bounds = bounds,
            children = kids,
        )
    }
    return snapshot(this)
}
