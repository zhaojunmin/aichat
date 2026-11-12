package com.octos.aichat.data.local

import com.octos.aichat.ai.AiClient
import com.octos.aichat.data.AppSettings
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * On-device LLM (MNN + Qwen2.5 Q4) as an [AiClient], so reply generation can run
 * fully offline — chat content never leaves the phone.
 *
 * The model is loaded once and kept resident; all native calls are serialised on
 * a single dedicated thread (MNN inference is blocking and not re-entrant). Only
 * arm64-v8a ships the native libs, so [isSupported] is false elsewhere and the
 * app falls back to the cloud client.
 */
object LocalLlmEngine : AiClient {

    // A few short replies as JSON fit well under this; capping avoids a rambling
    // model dragging out latency on slower on-device inference.
    private const val MAX_TOKENS = 180

    @Volatile private var available = false
    @Volatile private var handle: Long = 0L
    @Volatile private var loadedConfig: String? = null

    private val mutex = Mutex()
    // One dedicated thread: inference is a long blocking native call.
    private val infra = Executors.newSingleThreadExecutor { r ->
        Thread(r, "local-llm").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    init {
        available = runCatching {
            // Load in dependency order; the JNI bridge links the other three.
            System.loadLibrary("MNN")
            System.loadLibrary("MNN_Express")
            System.loadLibrary("llm")
            System.loadLibrary("llmjni")
            true
        }.getOrDefault(false)
    }

    /** True only where the native libs are present (arm64-v8a). */
    val isSupported: Boolean get() = available

    /** True once a model has been loaded and is ready to generate. */
    val isReady: Boolean get() = available && handle != 0L

    /**
     * Load the model described by [configPath] (an MNN `config.json`). Idempotent:
     * re-loading the same path is a no-op. [tmpDir] is used for kernel caches.
     * Returns true on success. Safe to call from any coroutine.
     */
    suspend fun ensureLoaded(configPath: String, tmpDir: String): Boolean = mutex.withLock {
        if (!available) return false
        if (handle != 0L && loadedConfig == configPath) return true
        if (handle != 0L) { nativeRelease(handle); handle = 0L; loadedConfig = null }
        val h = withContext(infra) { nativeLoad(configPath, tmpDir) }
        if (h != 0L) {
            handle = h; loadedConfig = configPath; true
        } else false
    }

    override suspend fun complete(settings: AppSettings, system: String, user: String): String {
        require(available) { "本地推理库不可用(仅 arm64 设备支持)" }
        return mutex.withLock {
            require(handle != 0L) { "本地模型尚未加载" }
            withContext(infra) { nativeChat(handle, system, user, MAX_TOKENS) }.trim()
        }
    }

    /** Free the model (e.g. when switching back to cloud to reclaim RAM). */
    suspend fun unload() = mutex.withLock {
        if (handle != 0L) { nativeRelease(handle); handle = 0L; loadedConfig = null }
    }

    private external fun nativeLoad(configPath: String, tmpDir: String): Long
    private external fun nativeChat(handle: Long, system: String, user: String, maxTokens: Int): String
    private external fun nativeRelease(handle: Long)
}
