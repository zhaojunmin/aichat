package com.octos.aichat.ai

import com.octos.aichat.data.AppSettings
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** A minimal single-turn text completion over some chat API. */
interface AiClient {
    /**
     * @param system the persona / behaviour instruction
     * @param user the actual request (transcript + task)
     * @return raw assistant text
     * @throws AiException on any request failure
     */
    suspend fun complete(settings: AppSettings, system: String, user: String): String
}

/**
 * A failed AI request, classified so the UI can tell the user what to do
 * (fix the key, top up, retry later…) instead of showing a raw HTTP body.
 * [httpCode] is 0 when the failure wasn't an HTTP response. [message] keeps the
 * technical detail for logs only — it is not meant for display.
 */
class AiException(
    val kind: Kind,
    val httpCode: Int = 0,
    detail: String = "",
    cause: Throwable? = null,
) : Exception(
    "$kind${if (httpCode > 0) " $httpCode" else ""}" +
        "${if (detail.isNotEmpty()) ": $detail" else ""}",
    cause,
) {

    enum class Kind {
        /** 401/403: key missing, wrong, or not allowed to use this model. */
        UNAUTHORIZED,

        /** Out of credit / account in arrears (DeepSeek 402, Ark AccountOverdue). */
        INSUFFICIENT_BALANCE,

        /** 429: rate limit or quota exhausted. */
        RATE_LIMITED,

        /** 404: wrong Base URL, model name or endpoint id. */
        NOT_FOUND,

        /** 400/422: request rejected, usually a bad model name / parameter. */
        BAD_REQUEST,

        /** 5xx: provider-side failure or overload. */
        SERVER,
        TIMEOUT,

        /** The on-device model didn't finish in time (device too slow for the chosen model). */
        LOCAL_TIMEOUT,

        /**
         * Local mode is selected but the on-device model isn't loaded. Never answered by
         * falling back to the cloud: that would ship the chat off the device.
         */
        LOCAL_NOT_READY,

        /** DNS / connect / TLS failure: no usable network path. */
        NETWORK,

        /** 2xx but not a usable completion (empty, or not the expected JSON). */
        BAD_RESPONSE,
        OTHER,
    }

    companion object {
        // Provider error codes that mean "no money", whatever the HTTP status
        // (Volcengine Ark reports arrears as 403 AccountOverdueError).
        private val BALANCE_MARKERS = listOf(
            "insufficient_balance",
            "insufficient balance",
            "accountoverdue",
            "overdue",
        )

        /** Classify a non-2xx response from an OpenAI-compatible endpoint. */
        fun fromHttp(code: Int, body: String): AiException {
            val lower = body.lowercase()
            val kind = when {
                code == 402 || BALANCE_MARKERS.any { it in lower } -> Kind.INSUFFICIENT_BALANCE
                code == 401 || code == 403 -> Kind.UNAUTHORIZED
                code == 429 -> Kind.RATE_LIMITED
                code == 404 -> Kind.NOT_FOUND
                code == 400 || code == 422 -> Kind.BAD_REQUEST
                code in 500..599 -> Kind.SERVER
                else -> Kind.OTHER
            }
            return AiException(kind, code, body.take(300))
        }

        /** Classify a transport-level failure (no HTTP response). */
        fun fromIo(e: Throwable): AiException = when (e) {
            is AiException -> e

            // SocketTimeoutException and OkHttp's call timeout are both InterruptedIOException.
            is InterruptedIOException -> AiException(Kind.TIMEOUT, cause = e)

            is UnknownHostException,
            is ConnectException,
            is NoRouteToHostException,
            is SSLException,
            ->
                AiException(Kind.NETWORK, detail = e.javaClass.simpleName, cause = e)

            else -> AiException(Kind.OTHER, detail = e.toString(), cause = e)
        }
    }
}
