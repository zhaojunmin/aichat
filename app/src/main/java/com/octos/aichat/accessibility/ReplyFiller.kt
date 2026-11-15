package com.octos.aichat.accessibility

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.octos.aichat.diagnostics.DiagLog
import com.octos.aichat.diagnostics.ErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drops a chosen reply into the chat's input box via `ACTION_SET_TEXT`.
 * [activeRoot] supplies the active window's root (the service's `rootInActiveWindow`).
 */
class ReplyFiller(private val activeRoot: () -> AccessibilityNodeInfo?) {

    /**
     * Fill the input-focused field if there is one (one IPC, and never a search box
     * elsewhere on screen), else the first editable node in the tree. Off the main
     * thread — the fallback walk is one IPC per node.
     * @return true if a field was filled; on false, `A11Y-305` is already recorded.
     */
    suspend fun fill(text: String, pkg: String?): Boolean = withContext(Dispatchers.IO) {
        val filled = try {
            val editable = activeRoot()?.let { root ->
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: findEditable(root)
            }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            editable?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) == true
        } catch (e: Exception) {
            DiagLog.record(ErrorCode.A11Y_305, cause = e, pkg = pkg)
            return@withContext false
        }
        if (!filled) DiagLog.record(ErrorCode.A11Y_305, pkg = pkg)
        filled
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            findEditable(node.getChild(i))?.let { return it }
        }
        return null
    }
}
