package com.octos.aichat.ui

import android.content.Context
import com.octos.aichat.R
import com.octos.aichat.ai.AiException
import com.octos.aichat.ai.AiException.Kind

/**
 * What to tell the user about a failed generation: an actionable, localized line
 * (check the key, top up, retry…) rather than a raw HTTP body. Non-AI failures
 * (e.g. "local model not ready") already carry a user-facing message.
 */
fun Throwable.toUserMessage(context: Context): String {
    val e = this as? AiException
        ?: return message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.generation_failed)
    return when (e.kind) {
        Kind.UNAUTHORIZED -> context.getString(R.string.ai_err_unauthorized)
        Kind.INSUFFICIENT_BALANCE -> context.getString(R.string.ai_err_balance)
        Kind.RATE_LIMITED -> context.getString(R.string.ai_err_rate_limited)
        Kind.NOT_FOUND -> context.getString(R.string.ai_err_not_found)
        Kind.BAD_REQUEST -> context.getString(R.string.ai_err_bad_request, e.httpCode)
        Kind.SERVER -> context.getString(R.string.ai_err_server, e.httpCode)
        Kind.TIMEOUT -> context.getString(R.string.ai_err_timeout)
        Kind.NETWORK -> context.getString(R.string.ai_err_network)
        Kind.BAD_RESPONSE -> context.getString(R.string.ai_err_bad_response)
        Kind.OTHER ->
            if (e.httpCode > 0) context.getString(R.string.ai_err_other_http, e.httpCode)
            else context.getString(R.string.generation_failed)
    }
}
