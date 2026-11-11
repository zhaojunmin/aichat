package com.octos.aichat.ai

import com.octos.aichat.data.AppSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val JSON = "application/json; charset=utf-8".toMediaType()

private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

private fun String.trimTrailingSlash() = trimEnd('/')

/**
 * DeepSeek Chat Completions API (/chat/completions). DeepSeek speaks the
 * OpenAI-compatible request/response shape.
 */
class DeepSeekClient : AiClient {
    override suspend fun complete(settings: AppSettings, system: String, user: String): String {
        val messages = JSONArray()
            .put(JSONObject().apply { put("role", "system"); put("content", system) })
            .put(JSONObject().apply { put("role", "user"); put("content", user) })
        val body = JSONObject().apply {
            put("model", settings.model)
            put("messages", messages)
            put("temperature", 0.8)
        }
        val url = settings.baseUrl.trimTrailingSlash() + "/chat/completions"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()

        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw AiException("DeepSeek ${resp.code}: ${text.take(300)}")
            val choices = JSONObject(text).optJSONArray("choices") ?: JSONArray()
            val msg = choices.optJSONObject(0)?.optJSONObject("message")
            return msg?.optString("content").orEmpty()
                .ifBlank { throw AiException("DeepSeek 返回为空") }
        }
    }
}
