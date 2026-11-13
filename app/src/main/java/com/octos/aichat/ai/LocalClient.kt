package com.octos.aichat.ai

import com.octos.aichat.ai.local.LlmEngine
import com.octos.aichat.data.AppSettings

/**
 * Thin [AiClient] over the on-device [LlmEngine] — parallel to the cloud
 * [CloudClient]. The engine must already be loaded ([LlmEngine.ensureLoaded]);
 * this just caps tokens and runs one inference turn.
 */
object LocalClient : AiClient {

    // A few short replies as JSON fit well under this; capping avoids a rambling
    // model dragging out latency on slower on-device inference.
    private const val MAX_TOKENS = 180

    override suspend fun complete(settings: AppSettings, system: String, user: String): String =
        LlmEngine.chat(system, user, MAX_TOKENS)
}
