package com.octos.aichat.ai

import com.octos.aichat.ai.local.LlmEngine
import com.octos.aichat.ai.local.LocalReadiness
import com.octos.aichat.bubble.SuggestionGenerator
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
    private val ready: suspend (AppSettings) -> LocalReadiness = { LocalReadiness.Ready }

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
     * When the on-device model can't run, the client says why — as an [AiException] with
     * its own kind and diagnostics code — and never reaches the engine. Callers handle it
     * like any other AiClient failure.
     */
    @Test fun unreadyModelFailsWithTheReason_andTheEngineIsNeverCalled() = runTest {
        val cases = listOf(
            LocalReadiness.Unsupported to (AiException.Kind.LOCAL_UNSUPPORTED to ErrorCode.LLM_203),
            LocalReadiness.NotDownloaded("1.5B") to
                (AiException.Kind.LOCAL_NOT_READY to ErrorCode.LLM_205),
            LocalReadiness.LoadFailed to (AiException.Kind.LOCAL_LOAD_FAILED to ErrorCode.LLM_202),
        )
        for ((readiness, expected) in cases) {
            var called = false
            val client = LocalClient(prepare = { readiness }) { _, _, _ ->
                called = true
                LlmEngine.Output("x", timedOut = false)
            }
            try {
                client.complete(AppSettings(), "sys", "user")
                fail("expected ${expected.first}")
            } catch (e: AiException) {
                assertEquals(expected.first, e.kind)
                assertEquals(expected.second, e.diagnosticsCode())
            }
            assertEquals("$readiness must not reach the engine", false, called)
        }
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
     * Through SuggestionGenerator: the local timeout reaches the caller as-is, not rewrapped as a cloud
     * TIMEOUT.
     */
    @Test fun localTimeoutSurfacesFromGenerateSuggestions() = runTest {
        val local = LocalClient(ready) { _, _, _ -> LlmEngine.Output("", timedOut = true) }
        val svc = SuggestionGenerator { local }
        val settings = AppSettings(inferenceMode = InferenceMode.LOCAL)
        try {
            svc.generate(
                settings,
                Conversation("pkg", listOf(ChatMessage(Speaker.OTHER, "hi"))),
            )
            fail("expected LOCAL_TIMEOUT")
        } catch (e: AiException) {
            assertEquals(AiException.Kind.LOCAL_TIMEOUT, e.kind)
        }
    }
}
