package com.octos.aichat.capture

import android.graphics.Bitmap
import android.graphics.Rect
import com.octos.aichat.accessibility.MessageBounds
import com.octos.aichat.accessibility.ScreenAccess
import com.octos.aichat.data.ChatMessage
import com.octos.aichat.data.Conversation
import com.octos.aichat.data.Speaker
import com.octos.aichat.debug.DebugCapture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** API 34: screenshots are available, so OCR-list apps take the OCR path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatCaptureTest {

    private val chat = Conversation("com.soul", listOf(ChatMessage(Speaker.OTHER, "在吗")))
    private val screen = FakeScreen(chat)
    private val overlay = FakeOverlay(screen)
    private val size = ScreenSize(1224, 2700)

    private fun capture(connected: ScreenAccess? = screen) =
        ChatCapture({ connected }, overlay, { size })

    @Before fun clearDebugRecord() = DebugCapture.recordConversation(Conversation.empty())

    @Test fun notConnectedReadsNothing() = runTest {
        assertNull(
            capture(connected = null).read("com.soul", ocrPackages = emptySet()).conversation,
        )
        assertNull(
            capture(connected = null).read("com.tencent.mm", setOf("com.tencent.mm")).conversation,
        )
    }

    @Test fun treeAppIsReadFromTheNodeTreeWithoutTouchingTheOverlay() = runTest {
        val result = capture().read("com.soul", ocrPackages = setOf("com.tencent.mm"))

        assertSame(chat, result.conversation)
        assertEquals(ReadPath.NODE_TREE, result.path)
        assertEquals(listOf("readConversation(com.soul)"), screen.calls)
        assertEquals(0, overlay.hides)
    }

    @Test fun whatWasReadIsHandedToTheDeveloperTools() = runTest {
        capture().read("com.soul", ocrPackages = emptySet())

        assertSame(chat, DebugCapture.lastConversation)
    }

    @Test fun ocrAppIsCapturedWithTheOverlayHidden() = runTest {
        val result = capture().read("com.tencent.mm", ocrPackages = setOf("com.tencent.mm"))

        assertEquals(ReadPath.OCR, result.path)
        assertEquals(
            listOf("locateMessageBounds(com.tencent.mm)", "captureScreenshot(com.tencent.mm)"),
            screen.calls,
        )
        assertTrue("geometry and pixels taken while hidden", screen.allCapturedWhileHidden)
        assertEquals(1, overlay.hides)
        assertFalse("overlay is shown again", overlay.hidden)
    }

    @Test fun failedScreenshotReadsNothingAndRecordsNothing() = runTest {
        val result = capture().read("com.tencent.mm", ocrPackages = setOf("com.tencent.mm"))

        assertNull(result.conversation)
        assertEquals("the hint must still say OCR", ReadPath.OCR, result.path)
        assertTrue(DebugCapture.lastConversation.isEmpty)
    }

    /** Records each call; the screenshot always fails (FLAG_SECURE, rate limit…). */
    private class FakeScreen(private val conv: Conversation) : ScreenAccess {
        val calls = mutableListOf<String>()
        var overlayHidden: () -> Boolean = { false }
        var allCapturedWhileHidden = true

        private fun record(call: String) {
            calls += call
            if (call.startsWith("locate") || call.startsWith("capture")) {
                allCapturedWhileHidden = allCapturedWhileHidden && overlayHidden()
            }
        }

        override suspend fun readConversation(pkg: String): Conversation {
            record("readConversation($pkg)")
            return conv
        }

        override suspend fun locateMessageBounds(pkg: String): List<MessageBounds> {
            record("locateMessageBounds($pkg)")
            return emptyList()
        }

        override suspend fun captureScreenshot(pkg: String): Bitmap? {
            record("captureScreenshot($pkg)")
            return null
        }

        override fun imeBounds(): Rect? = null

        override suspend fun fill(text: String, pkg: String?): Boolean = false
    }

    private class FakeOverlay(screen: FakeScreen) : CaptureOverlay {
        var hidden = false
        var hides = 0

        init {
            screen.overlayHidden = { hidden }
        }

        override suspend fun <T> hiddenDuring(block: suspend () -> T): T {
            hidden = true
            hides++
            try {
                return block()
            } finally {
                hidden = false
            }
        }
    }
}
