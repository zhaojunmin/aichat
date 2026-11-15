package com.octos.aichat.ai

import com.octos.aichat.ai.local.LlmEngine
import com.octos.aichat.ai.local.LocalReadiness
import com.octos.aichat.data.AppSettings

/**
 * [AiClient] over the on-device [LlmEngine] — parallel to the cloud [CloudClient]. Gets
 * the selected model ready itself (supported device → downloaded → loaded), caps tokens
 * and runs one inference turn.
 *
 * Fails like any [AiClient] does — with an [AiException] saying why — so callers needn't
 * know which implementation they hold, nor touch the engine.
 *
 * [prepare] and [infer] are the engine calls, injected so the error mapping can be
 * unit-tested without the native library.
 */
internal class LocalClient(
    private val prepare: suspend (AppSettings) -> LocalReadiness = { LlmEngine.prepare(it) },
    private val infer: suspend (system: String, user: String, maxTokens: Int) -> LlmEngine.Output =
        { system, user, maxTokens -> LlmEngine.chat(system, user, maxTokens) },
) : AiClient {

    override suspend fun complete(settings: AppSettings, system: String, user: String): String {
        when (val readiness = prepare(settings)) {
            LocalReadiness.Ready -> Unit

            LocalReadiness.Unsupported -> throw AiException(AiException.Kind.LOCAL_UNSUPPORTED)

            is LocalReadiness.NotDownloaded -> throw AiException(
                AiException.Kind.LOCAL_NOT_READY,
                detail = "${readiness.modelName} not downloaded",
            )

            LocalReadiness.LoadFailed -> throw AiException(AiException.Kind.LOCAL_LOAD_FAILED)
        }
        val out = infer(system, user, MAX_TOKENS)
        // Truncated output would parse into fragments; report the timeout instead.
        if (out.timedOut) {
            throw AiException(
                AiException.Kind.LOCAL_TIMEOUT,
                detail = "local engine hit ${LlmEngine.GENERATION_TIMEOUT_MS}ms",
            )
        }
        return out.text
    }

    companion object {
        // A few short suggestions as JSON fit well under this; capping avoids a rambling
        // model dragging out latency on slower on-device inference.
        const val MAX_TOKENS = 180
    }
}
