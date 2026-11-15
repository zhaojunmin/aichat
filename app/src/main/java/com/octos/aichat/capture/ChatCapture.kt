package com.octos.aichat.capture

import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.RequiresApi
import com.octos.aichat.accessibility.MessageBounds
import com.octos.aichat.accessibility.ScreenAccess
import com.octos.aichat.data.Conversation
import com.octos.aichat.debug.DebugCapture
import com.octos.aichat.diagnostics.DiagLog
import com.octos.aichat.diagnostics.ErrorCode
import com.octos.aichat.diagnostics.TimedOp
import com.octos.aichat.diagnostics.Tracing
import com.octos.aichat.ocr.OcrReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Our own window on screen, kept out of screenshots by hiding it for the capture. */
interface CaptureOverlay {
    /** Hide the overlay, run [block], and always show it again — even on cancellation. */
    suspend fun <T> hiddenDuring(block: suspend () -> T): T
}

data class ScreenSize(val width: Int, val height: Int)

/** Which way the chat was read. Decides, for one, which "nothing was read" hint fits. */
enum class ReadPath { NODE_TREE, OCR }

/** What one read produced and how; [conversation] is null when nothing could be read. */
data class CaptureResult(val conversation: Conversation?, val path: ReadPath)

/**
 * Reads the chat in front, the way a bubble tap needs it: the node tree by default;
 * for apps in the OCR list (tree blocked or too noisy, WeChat by default), a
 * screenshot + on-device OCR, guided by message geometry when the app exposes it.
 *
 * Reads what is on screen at the moment of the call — never cached state.
 */
class ChatCapture(
    private val screen: () -> ScreenAccess?,
    private val overlay: CaptureOverlay,
    private val screenSize: () -> ScreenSize,
) {

    /**
     * The path is decided here and only here: OCR for apps in [ocrPackages] (when the
     * platform can take screenshots), the node tree otherwise. The conversation is also
     * handed to [DebugCapture]; it is null when the accessibility service isn't
     * connected or the screenshot failed.
     */
    suspend fun read(pkg: String, ocrPackages: Set<String>): CaptureResult =
        if (pkg in ocrPackages && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            CaptureResult(readViaOcr(pkg), ReadPath.OCR)
        } else {
            CaptureResult(readFromNodeTree(pkg), ReadPath.NODE_TREE)
        }

    private suspend fun readFromNodeTree(pkg: String): Conversation? {
        val conv = DiagLog.timed(TimedOp.PARSE) { screen()?.readConversation(pkg) } ?: return null
        DebugCapture.recordConversation(conv)
        return conv
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun readViaOcr(pkg: String): Conversation? {
        val screen = screen() ?: return null
        // Grab message geometry from the node tree at the same moment we capture, so
        // rects and pixels line up. Empty for apps with no geometry (WeChat).
        val (messageBounds, bmp) = overlay.hiddenDuring {
            screen.locateMessageBounds(pkg) to screen.captureScreenshot(pkg)
        }
        bmp ?: return null

        // When the soft keyboard is up, its letters get OCR'd as chat text. Crop the
        // screenshot to just above the IME window before the whole-screen read.
        val imeTop = screen.imeBounds()?.top ?: -1
        val size = screenSize()
        val conv = withContext(Dispatchers.Default) {
            Tracing.asyncSection(Tracing.BUBBLE_OCR) {
                DiagLog.timed(TimedOp.OCR) { recognize(bmp, messageBounds, imeTop, size, pkg) }
            }
        }
        bmp.recycle()
        if (conv.isEmpty) DiagLog.record(ErrorCode.OCR_402, pkg = pkg)
        DebugCapture.recordConversation(conv)
        return conv
    }

    /**
     * Geometry-guided OCR when we have message bounds (Momo); otherwise the
     * whole-screen colour/region heuristic (WeChat), above the keyboard if it is up.
     */
    private suspend fun recognize(
        bmp: Bitmap,
        messageBounds: List<MessageBounds>,
        imeTop: Int,
        size: ScreenSize,
        pkg: String,
    ): Conversation = when {
        messageBounds.isNotEmpty() -> OcrReader.readConversation(bmp, messageBounds, pkg)

        imeTop in 1 until bmp.height -> {
            val cropped = Bitmap.createBitmap(bmp, 0, 0, bmp.width, imeTop)
            val c = OcrReader.readConversation(cropped, size.width, imeTop, pkg)
            cropped.recycle()
            c
        }

        else -> OcrReader.readConversation(bmp, size.width, size.height, pkg)
    }
}
