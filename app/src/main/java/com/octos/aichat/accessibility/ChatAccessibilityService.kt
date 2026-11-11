package com.octos.aichat.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.octos.aichat.AppBus
import com.octos.aichat.data.SettingsRepository
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

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
        if (!isTarget) return

        captureTree(root, pkg)
        // OCR-managed apps (WeChat/Hertz) are read via screenshot on bubble tap;
        // don't node-parse them, or the noisy node result would overwrite the
        // clean OCR conversation.
        if (pkg in settings.ocrPackageSet) return
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
