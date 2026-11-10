package com.octos.aichat.ai

import com.octos.aichat.data.AppSettings

/** A minimal single-turn text completion over some chat API. */
interface AiClient {
    /**
     * @param system the persona / behaviour instruction
     * @param user the actual request (transcript + task)
     * @return raw assistant text
     */
    suspend fun complete(settings: AppSettings, system: String, user: String): String
}

class AiException(message: String) : Exception(message)
