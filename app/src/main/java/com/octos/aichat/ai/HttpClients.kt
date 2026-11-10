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

/** Anthropic Messages API (/v1/messages). */
class ClaudeClient : AiClient {
    override suspend fun complete(settings: AppSettings, system: String, user: String): String {
        val body = JSONObject().apply {
            put("model", settings.model)
            put("max_tokens", 1024)
            put("system", system)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", user)
            }))
        }
        val url = settings.baseUrl.trimTrailingSlash() + "/v1/messages"
        val request = Request.Builder()
            .url(url)
            .header("x-api-key", settings.apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()

        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw AiException("Claude ${resp.code}: ${text.take(300)}")
            val content = JSONObject(text).optJSONArray("content") ?: JSONArray()
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") sb.append(block.optString("text"))
            }
            return sb.toString().ifBlank { throw AiException("Claude 返回为空") }
        }
    }
}

/** OpenAI-compatible Chat Completions API (/chat/completions). */
class OpenAiClient : AiClient {
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
            if (!resp.isSuccessful) throw AiException("OpenAI ${resp.code}: ${text.take(300)}")
            val choices = JSONObject(text).optJSONArray("choices") ?: JSONArray()
            val msg = choices.optJSONObject(0)?.optJSONObject("message")
            return msg?.optString("content").orEmpty()
                .ifBlank { throw AiException("OpenAI 返回为空") }
        }
    }
}
