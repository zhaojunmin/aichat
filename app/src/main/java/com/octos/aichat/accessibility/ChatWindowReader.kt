package com.octos.aichat.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.octos.aichat.data.Conversation
import com.octos.aichat.diagnostics.DiagLog
import com.octos.aichat.diagnostics.ErrorCode
import com.octos.aichat.diagnostics.Tracing
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * What the overlay needs from the screen on a bubble tap: the chat as parsed from
 * the node tree, or the pieces for the OCR path (bubble geometry, a screenshot,
 * the keyboard area to crop away). Built on [service]'s window APIs; holds no state.
 */
class ChatWindowReader(private val service: AccessibilityService) {

    private val screenWidth: Int get() = service.resources.displayMetrics.widthPixels
    private val screenHeight: Int get() = service.resources.displayMetrics.heightPixels

    /**
     * Parse [pkg]'s chat window as it is right now; empty when it can't be read. Off the main
     * thread.
     */
    suspend fun readConversation(pkg: String): Conversation = withContext(Dispatchers.IO) {
        try {
            Tracing.section(Tracing.A11Y_PARSE) {
                val root = windowRoot(pkg) ?: run {
                    DiagLog.record(ErrorCode.A11Y_303, pkg = pkg)
                    return@withContext Conversation(pkg, emptyList())
                }
                ChatParser.parse(root, screenWidth, screenHeight, pkg)
            }
        } catch (e: Exception) {
            DiagLog.record(ErrorCode.A11Y_302, cause = e, pkg = pkg)
            Conversation(pkg, emptyList())
        }
    }

    /**
     * Root of [pkg]'s app window. Looked up by package rather than "active window"
     * because the tap itself lands on our overlay; falls back to the active window.
     */
    private fun windowRoot(pkg: String): AccessibilityNodeInfo? =
        // getRoot() is a cross-process call: fetch it once per window.
        service.windows.orEmpty().asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() == pkg }
            ?: service.rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }

    /**
     * Chat message bounds for apps with a [MessageBoundsLocator] rule; empty otherwise. Reads
     * the same window as [readConversation] — [pkg]'s own, not whichever window is active
     * (our overlay or the keyboard may be). A full tree walk, so off the main thread.
     */
    suspend fun locateMessageBounds(pkg: String): List<MessageBounds> {
        if (!MessageBoundsLocator.hasRule(pkg)) return emptyList()
        return withContext(Dispatchers.IO) {
            MessageBoundsLocator.locate(windowRoot(pkg), screenWidth, pkg)
        }
    }

    /**
     * Capture the current screen via the accessibility API — no MediaProjection
     * consent dialog needed. Returns a software ARGB_8888 bitmap ready for OCR,
     * or null on failure (e.g. a FLAG_SECURE window, or rate limiting).
     */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun captureScreenshot(pkg: String): Bitmap? = suspendCancellableCoroutine { cont ->
        // takeScreenshot() can throw synchronously (e.g. SecurityException if the
        // capability is missing). Guard it so a failure never crashes the whole
        // accessibility service process.
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                // Off the main thread: the callback copies the full-screen hardware
                // bitmap into a software one (~13 MB at 1224×2700).
                Dispatchers.Default.asExecutor(),
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val software = try {
                            val hw = Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer,
                                result.colorSpace,
                            )
                            val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hw?.recycle()
                            copy
                        } catch (e: Exception) {
                            DiagLog.record(ErrorCode.A11Y_304, cause = e, pkg = pkg)
                            null
                        } finally {
                            result.hardwareBuffer.close()
                        }
                        // A cancelled tap must not leak the ~13 MB copy.
                        cont.resume(software) { software?.recycle() }
                    }

                    override fun onFailure(errorCode: Int) {
                        DiagLog.record(ErrorCode.A11Y_304, platform = errorCode, pkg = pkg)
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        } catch (e: Exception) {
            DiagLog.record(ErrorCode.A11Y_304, cause = e, pkg = pkg)
            if (cont.isActive) cont.resume(null)
        }
    }

    /**
     * Screen bounds of the soft keyboard if it is showing, else null. Used to crop
     * the keyboard out of OCR screenshots so its letters aren't read as chat.
     */
    fun imeBounds(): Rect? = try {
        service.windows
            ?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { w -> Rect().also { w.getBoundsInScreen(it) } }
            ?.takeIf { !it.isEmpty }
    } catch (e: Exception) {
        DiagLog.record(ErrorCode.A11Y_306, cause = e)
        null
    }
}
