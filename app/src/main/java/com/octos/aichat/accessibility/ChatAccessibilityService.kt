package com.octos.aichat.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.octos.aichat.AppBus
import com.octos.aichat.Tracing
import com.octos.aichat.data.Conversation
import com.octos.aichat.data.SettingsRepository
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Tracks which app is in the foreground (to show/hide the bubble) and, on bubble
 * tap, reads the chat window of supported apps ([readConversation]). Also exposes [pasteText] so the overlay can drop a chosen reply
 * straight into the input box.
 */
class ChatAccessibilityService : AccessibilityService() {

    private val screenWidth: Int
        get() = resources.displayMetrics.widthPixels

    private val screenHeight: Int
        get() = resources.displayMetrics.heightPixels

    // Debug-only: the raw-tree capture walks the whole node tree (one IPC per
    // child), so it runs on its own thread, never the main thread events arrive on.
    private val captureThread = HandlerThread("a11y-capture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)
    private val captureTask = Runnable { guarded(::captureActiveWindow) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppBus.accessibilityService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        Tracing.section(Tracing.A11Y_EVENT) { guarded(::onWindowChanged) }
    }

    /**
     * An exception escaping an accessibility callback crashes the process, and the
     * system then disables the service until the user re-enables it by hand. A
     * failed read is harmless (the next event retries), so never let one escape.
     */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "accessibility read failed", e)
        }
    }

    /**
     * Main thread, on every event: cheap foreground tracking only. The chat itself
     * is parsed on demand ([readConversation]) when the user taps the bubble — no
     * per-event tree walks while they're just chatting.
     */
    private fun onWindowChanged() {
        // Drive foreground state only from the *active window's* package. The IME,
        // toasts and transient sub-windows don't own the active window, so this
        // avoids false "left the chat app" signals (which would hide the bubble).
        //
        // Crucially, when rootInActiveWindow is momentarily null (common during
        // transitions / when the keyboard opens) we must NOT fall back to
        // event.packageName — that package often belongs to the IME or a system
        // window, which would wrongly hide the bubble and leave it stuck hidden.
        // Skipping the event keeps the last known state until a reliable one.
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        val settings = SettingsRepository.get(this).current()
        val isTarget = pkg in settings.targetPackageSet

        AppBus.onForeground(pkg, isTarget)
        if (!isTarget || !AppBus.captureRawTree) return

        // Coalesce bursts: a queued capture reads the window when it runs, so one
        // pending task is enough — later events just replace it.
        captureHandler.removeCallbacks(captureTask)
        captureHandler.post(captureTask)
    }

    /**
     * Parse the chat window of [pkg] as it is right now. Called on bubble tap; the
     * tree walk runs off the main thread. Empty when that window can't be read.
     */
    suspend fun readConversation(pkg: String): Conversation = withContext(Dispatchers.IO) {
        try {
            Tracing.section(Tracing.A11Y_PARSE) {
                val root = windowRoot(pkg) ?: return@withContext Conversation(pkg, emptyList())
                ChatParser.parse(root, screenWidth, screenHeight, pkg)
            }
        } catch (e: Exception) {
            Log.w(TAG, "accessibility read failed", e)
            Conversation(pkg, emptyList())
        }
    }

    /**
     * Root of [pkg]'s app window. Looked up by package rather than "active window"
     * because the tap itself lands on our overlay; falls back to the active window.
     */
    private fun windowRoot(pkg: String): AccessibilityNodeInfo? =
        windows
            ?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName == pkg }
            ?.root
            ?: rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }

    /** [captureThread], debug switch on: re-read the active window (it may have moved on) and capture it. */
    private fun captureActiveWindow() {
        if (!AppBus.captureRawTree) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg !in SettingsRepository.get(this).current().targetPackageSet) return
        captureTree(root, pkg)
    }

    // Capture the node tree into AppBus so the in-app debug viewer can show it.
    // This is how we design per-app parse rules without relying on logcat /
    // uiautomator, both of which are blocked on some devices/apps.
    // Only touched on captureThread.
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

    /**
     * Capture the current screen via the accessibility API — no MediaProjection
     * consent dialog needed. Returns a software ARGB_8888 bitmap ready for OCR,
     * or null on failure (e.g. a FLAG_SECURE window, or rate limiting).
     */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun captureScreenshot(): Bitmap? = suspendCancellableCoroutine { cont ->
        // takeScreenshot() can throw synchronously (e.g. SecurityException if the
        // capability is missing). Guard it so a failure never crashes the whole
        // accessibility service process.
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val software = try {
                            val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hw?.recycle()
                            copy
                        } catch (_: Exception) {
                            null
                        } finally {
                            result.hardwareBuffer.close()
                        }
                        if (cont.isActive) cont.resume(software)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            )
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    /**
     * Screen bounds of the on-screen IME (soft keyboard) if it's currently shown,
     * else null. Used to crop the keyboard out of OCR screenshots so its letters
     * aren't misread as chat text.
     */
    fun imeBounds(): Rect? = try {
        windows
            ?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { w -> Rect().also { w.getBoundsInScreen(it) } }
            ?.takeIf { !it.isEmpty }
    } catch (_: Exception) {
        null
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        captureHandler.removeCallbacksAndMessages(null)
        captureThread.quitSafely()
        if (AppBus.accessibilityService === this) AppBus.accessibilityService = null
    }

    private companion object {
        const val TAG = "ChatA11y"
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
