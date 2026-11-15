package com.octos.aichat.ai

import com.octos.aichat.ai.local.LlmEngine
import com.octos.aichat.data.AppSettings
import com.octos.aichat.data.ChatMessage
import com.octos.aichat.data.Conversation
import com.octos.aichat.data.InferenceMode
import com.octos.aichat.data.Speaker
import com.octos.aichat.diagnostics.ErrorCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** The on-device path's failure mapping, with the native engine faked out. */
class LocalClientTest {

    /** The engine is loaded unless a test says otherwise. */
    private val ready: () -> Boolean = { true }

    @Test fun normalOutputPassesThrough_withTokenCap() = runTest {
        var cap = 0
        val client =
            LocalClient(ready) { _, _, max ->
                cap = max
                LlmEngine.Output("""["好"]""", timedOut = false)
            }

        assertEquals("""["好"]""", client.complete(AppSettings(), "sys", "user"))
        assertEquals(LocalClient.MAX_TOKENS, cap)
    }

    /**
     * An unloaded model fails like any client does — as an [AiException] — instead of an
     * IllegalArgumentException that diagnostics could only file under "other".
     */
    @Test fun unloadedModelIsLocalNotReady_andTheEngineIsNeverCalled() = runTest {
        var called = false
        val client = LocalClient(isReady = { false }) { _, _, _ ->
            called = true
            LlmEngine.Output("x", timedOut = false)
        }
        try {
            client.complete(AppSettings(), "sys", "user")
            fail("expected LOCAL_NOT_READY")
        } catch (e: AiException) {
            assertEquals(AiException.Kind.LOCAL_NOT_READY, e.kind)
            assertEquals(ErrorCode.LLM_205, e.diagnosticsCode())
        }
        assertEquals(false, called)
    }

    /**
     * A truncated answer must not be parsed into fragments — and must not read as a network
     * problem.
     */
    @Test fun engineTimeoutIsLocalTimeout() = runTest {
        val client = LocalClient(ready) { _, _, _ -> LlmEngine.Output("""["半截""", timedOut = true) }
        try {
            client.complete(AppSettings(), "sys", "user")
            fail("expected LOCAL_TIMEOUT")
        } catch (e: AiException) {
            assertEquals(AiException.Kind.LOCAL_TIMEOUT, e.kind)
        }
    }

    /** After a timeout the next turn is independent (the engine resets itself natively). */
    @Test fun nextTurnAfterTimeoutSucceeds() = runTest {
        val outputs =
            ArrayDeque(
                listOf(
                    LlmEngine.Output("", timedOut = true),
                    LlmEngine.Output("ok", timedOut = false),
                ),
            )
        val client = LocalClient(ready) { _, _, _ -> outputs.removeFirst() }

        runCatching { client.complete(AppSettings(), "s", "u") }
        assertEquals("ok", client.complete(AppSettings(), "s", "u"))
    }

    /**
     * Through AiService: the local timeout reaches the caller as-is, not rewrapped as a cloud
     * TIMEOUT.
     */
    @Test fun localTimeoutSurfacesFromGenerateReplies() = runTest {
        val local = LocalClient(ready) { _, _, _ -> LlmEngine.Output("", timedOut = true) }
        val svc = AiService { local }
        val settings = AppSettings(inferenceMode = InferenceMode.LOCAL)
        try {
            svc.generateReplies(
                settings,
                Conversation("pkg", listOf(ChatMessage(Speaker.OTHER, "hi"))),
            )
            fail("expected LOCAL_TIMEOUT")
        } catch (e: AiException) {
            assertEquals(AiException.Kind.LOCAL_TIMEOUT, e.kind)
        }
    }
}
