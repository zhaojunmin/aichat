package com.octos.aichat.ai

import com.octos.aichat.ai.local.LlmEngine
import com.octos.aichat.data.AppSettings

/**
 * Thin [AiClient] over the on-device [LlmEngine] — parallel to the cloud
 * [CloudClient]. The engine must already be loaded ([LlmEngine.ensureLoaded]);
 * this just caps tokens and runs one inference turn.
 *
 * [infer] is the engine call, injected so the timeout mapping can be unit-tested
 * without the native library.
 */
class LocalClient(
    private val infer: suspend (system: String, user: String, maxTokens: Int) -> LlmEngine.Output =
        { system, user, maxTokens -> LlmEngine.chat(system, user, maxTokens) },
) : AiClient {

    override suspend fun complete(settings: AppSettings, system: String, user: String): String {
        val out = infer(system, user, MAX_TOKENS)
        // Truncated output would parse into fragments; report the timeout instead.
        if (out.timedOut) {
            throw AiException(AiException.Kind.LOCAL_TIMEOUT, detail = "local engine hit ${LlmEngine.GENERATION_TIMEOUT_MS}ms")
        }
        return out.text
    }

    companion object {
        // A few short replies as JSON fit well under this; capping avoids a rambling
        // model dragging out latency on slower on-device inference.
        const val MAX_TOKENS = 180
    }
}
