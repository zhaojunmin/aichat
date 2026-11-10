package com.octos.aichat.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.octos.aichat.AppBus
import com.octos.aichat.data.SettingsRepository

/**
 * Reads the active window of supported chat apps and keeps [AppBus.conversation]
 * up to date. Also exposes [pasteText] so the overlay can drop a chosen reply
 * straight into the input box.
 */
class ChatAccessibilityService : AccessibilityService() {

    private val screenWidth: Int
        get() = resources.displayMetrics.widthPixels

    private val screenHeight: Int
        get() = resources.displayMetrics.heightPixels

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppBus.accessibilityService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // The active window's package reflects the real foreground app; the IME
        // and transient sub-windows don't own it, so this avoids false "left the
        // chat app" signals while typing.
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: event.packageName?.toString() ?: return
        val targets = SettingsRepository.get(this).current().targetPackageSet
        val isTarget = pkg in targets

        AppBus.onForeground(pkg, isTarget)
        if (!isTarget || root == null) return

        captureTree(root, pkg)
        val conv = ChatParser.parse(root, screenWidth, screenHeight, pkg)
        if (!conv.isEmpty) AppBus.updateConversation(conv)
    }

    // Capture the node tree into AppBus so the in-app debug viewer can show it.
    // This is how we design per-app parse rules without relying on logcat /
    // uiautomator, both of which are blocked on some devices/apps.
    private var lastDumpAt = 0L
    private fun captureTree(root: AccessibilityNodeInfo, pkg: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastDumpAt < 1500) return
        lastDumpAt = now
        val sb = StringBuilder()
        sb.append("来源: ").append(pkg).append("\n\n")
        var count = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || count > 300) return
            val txt = (n.text ?: n.contentDescription)?.toString()?.trim()
            val vid = n.viewIdResourceName
            if (!txt.isNullOrEmpty() || vid != null) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                val cls = n.className?.toString()?.substringAfterLast('.')
                sb.append("[").append(cls).append("] id=")
                    .append(vid?.substringAfterLast('/') ?: "-")
                    .append(" ").append(r.toShortString())
                if (!txt.isNullOrEmpty()) sb.append(" t=").append(txt.take(40))
                sb.append('\n')
                count++
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        sb.append("\n共 ").append(count).append(" 个有文本/ID 的节点")
        AppBus.lastRawDump = sb.toString()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (AppBus.accessibilityService === this) AppBus.accessibilityService = null
    }

    /**
     * Find the focused/visible editable field and set [text] into it.
     * @return true if a field was found and filled.
     */
    fun pasteText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val editable = findEditable(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        return editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
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
