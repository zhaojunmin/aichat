package com.octos.aichat.accessibility

import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.octos.aichat.capture.DebugCapture
import com.octos.aichat.diagnostics.DiagLog
import com.octos.aichat.diagnostics.ErrorCode

/**
 * Debug only: dumps the active window's node tree (id / class / bounds / text) into
 * [DebugCapture.lastRawDump] for the in-app viewer — how per-app parse rules are designed
 * without logcat / uiautomator, both blocked on some devices. Runs only while
 * [DebugCapture.captureRawTree] is on; its worker thread starts on first use.
 */
class RawTreeDumper(
    private val activeRoot: () -> AccessibilityNodeInfo?,
    private val isTarget: (String) -> Boolean,
) {
    // The walk is one IPC per child, so it never runs on the main thread.
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val dumpTask = Runnable {
        try {
            dumpActiveWindow()
        } catch (e: Exception) {
            DiagLog.record(ErrorCode.A11Y_301, cause = e)
        }
    }

    // Only touched on the worker thread.
    private var lastDumpAt = 0L

    val enabled: Boolean get() = DebugCapture.captureRawTree

    /**
     * Main thread: schedule a dump. Bursts coalesce — a queued dump reads the window when it runs.
     */
    fun request() {
        if (!enabled) return
        val h =
            handler
                ?: Handler(
                    HandlerThread("a11y-raw-tree").also {
                        it.start()
                        thread = it
                    }.looper,
                )
                    .also { handler = it }
        h.removeCallbacks(dumpTask)
        h.post(dumpTask)
    }

    fun close() {
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        handler = null
        thread = null
    }

    /** Re-read the active window (it may have moved on since the request) and dump it. */
    private fun dumpActiveWindow() {
        if (!enabled) return
        val root = activeRoot() ?: return
        val pkg = root.packageName?.toString() ?: return
        if (!isTarget(pkg)) return
        val now = SystemClock.uptimeMillis()
        if (now - lastDumpAt < MIN_INTERVAL_MS) return
        lastDumpAt = now
        DebugCapture.lastRawDump = render(root, pkg)
    }

    private fun render(root: AccessibilityNodeInfo, pkg: String): String {
        val sb = StringBuilder()
        sb.append("来源: ").append(pkg).append("\n\n")
        var count = 0
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || count > MAX_NODES) return
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
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        sb.append("\n共 ").append(count).append(" 个有文本/ID 的节点")
        return sb.toString()
    }

    private companion object {
        const val MIN_INTERVAL_MS = 1500L
        const val MAX_NODES = 300
    }
}
