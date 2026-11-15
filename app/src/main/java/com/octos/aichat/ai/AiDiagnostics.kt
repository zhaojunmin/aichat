package com.octos.aichat.ai

import com.octos.aichat.ai.AiException.Kind
import com.octos.aichat.diagnostics.DiagLog
import com.octos.aichat.diagnostics.ErrorCode

/**
 * Diagnostics code for a failed generation (docs/observability-design.md §4.2).
 * Lives in `ai` so the `diagnostics` package never depends on it. Anything that
 * isn't an [AiException] (e.g. "no API key set") is [ErrorCode.AI_110].
 */
fun Throwable.diagnosticsCode(): ErrorCode = when ((this as? AiException)?.kind) {
    Kind.UNAUTHORIZED -> ErrorCode.AI_101
    Kind.INSUFFICIENT_BALANCE -> ErrorCode.AI_102
    Kind.RATE_LIMITED -> ErrorCode.AI_103
    Kind.NOT_FOUND -> ErrorCode.AI_104
    Kind.BAD_REQUEST -> ErrorCode.AI_105
    Kind.SERVER -> ErrorCode.AI_106
    Kind.TIMEOUT -> ErrorCode.AI_107
    Kind.NETWORK -> ErrorCode.AI_108
    Kind.BAD_RESPONSE -> ErrorCode.AI_109
    Kind.LOCAL_TIMEOUT -> ErrorCode.LLM_201
    Kind.LOCAL_NOT_READY -> ErrorCode.LLM_205
    Kind.OTHER, null -> ErrorCode.AI_110
}

/** Record a failed AI request: its code, exception class and HTTP status (if any). */
fun DiagLog.recordAiFailure(e: Throwable, ms: Long? = null) = record(
    e.diagnosticsCode(),
    cause = e,
    ms = ms,
    http = (e as? AiException)?.httpCode?.takeIf {
        it >
            0
    },
)
