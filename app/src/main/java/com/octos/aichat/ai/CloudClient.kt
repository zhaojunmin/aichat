package com.octos.aichat.ai

import com.octos.aichat.data.AppSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val JSON = "application/json; charset=utf-8".toMediaType()

private fun String.trimTrailingSlash() = trimEnd('/')

/**
 * OpenAI-compatible Chat Completions API (/chat/completions). Both DeepSeek and
 * Doubao (Volcengine Ark) speak this shape, differing only in Base URL + model.
 *
 * Timeouts here are a backstop; the interactive budget for a reply is enforced by
 * [AiService]. Cancelling the calling coroutine cancels the HTTP call, so a
 * superseded request (user tapped again) stops using the network immediately.
 */
class CloudClient(private val http: OkHttpClient = defaultHttp) : AiClient {

    override suspend fun complete(settings: AppSettings, system: String, user: String): String {
        val messages = JSONArray()
            .put(JSONObject().apply { put("role", "system"); put("content", system) })
            .put(JSONObject().apply { put("role", "user"); put("content", user) })
        val body = JSONObject().apply {
            put("model", settings.model)
            put("messages", messages)
            put("temperature", 0.8)
        }
        val request = try {
            Request.Builder()
                .url(settings.baseUrl.trimTrailingSlash() + "/chat/completions")
                .header("Authorization", "Bearer ${settings.apiKey}")
                .header("content-type", "application/json")
                .post(body.toString().toRequestBody(JSON))
                .build()
        } catch (e: IllegalArgumentException) {
            // Malformed Base URL (e.g. missing https://).
            throw AiException(AiException.Kind.NOT_FOUND, detail = "bad base url", cause = e)
        }

        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            throw AiException.fromIo(e)
        }
        response.use { resp ->
            val text = try {
                resp.body?.string().orEmpty()
            } catch (e: IOException) {
                throw AiException.fromIo(e)
            }
            if (!resp.isSuccessful) throw AiException.fromHttp(resp.code, text)
            return parseContent(text)
        }
    }

    companion object {
        private val defaultHttp: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // Whole-call ceiling. Generous because background memory learning
            // produces longer output than an interactive reply.
            .callTimeout(90, TimeUnit.SECONDS)
            .build()

        internal fun parseContent(text: String): String {
            val content = try {
                JSONObject(text).optJSONArray("choices")
                    ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            } catch (e: JSONException) {
                throw AiException(AiException.Kind.BAD_RESPONSE, detail = text.take(120), cause = e)
            }
            if (content.isNullOrBlank()) throw AiException(AiException.Kind.BAD_RESPONSE, detail = "empty content")
            return content
        }
    }
}

/** Suspend until the call completes; cancelling the coroutine cancels the call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            // If the coroutine was cancelled meanwhile, release the connection.
            cont.resume(response) { response.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    })
}
