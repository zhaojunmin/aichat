package com.octos.aichat.ai.local

import android.content.Context
import android.os.SystemClock
import com.octos.aichat.Tracing
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * On-device LLM engine (MNN + Qwen2.5 Q4): owns the native model lifecycle —
 * loading/holding the model, the native handle, and serialising inference on one
 * dedicated thread (MNN calls are blocking and not re-entrant). Only arm64-v8a
 * ships the native libs, so [isSupported] is false elsewhere.
 *
 * This is the *engine*; [LocalClient] is the thin [AiClient] over it (so reply
 * generation can run fully offline — chat content never leaves the phone).
 */
object LlmEngine {

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
        val h = withContext(infra) { nativeLoad(configPath, tmpDir, GENERATION_TIMEOUT_MS.toInt()) }
        if (h != 0L) {
            handle = h; loadedConfig = configPath; true
        } else false
    }

    /**
     * Load [model] from its downloaded files. False when the native libs are
     * unsupported on this device, the model isn't downloaded, or loading fails —
     * callers that need to tell those apart check [isSupported] / [ModelStore.isReady] first.
     */
    suspend fun ensureLoaded(ctx: Context, model: LocalModel): Boolean {
        if (!available || !ModelStore.isReady(ctx, model)) return false
        return ensureLoaded(ModelStore.configPath(ctx, model), ModelStore.tmpDir(ctx))
    }

    /** One inference turn's output; [timedOut] means the engine hit its time limit and the text is truncated. */
    class Output(val text: String, val timedOut: Boolean)

    /**
     * Run one inference turn on the loaded model. Serialised; blocking native call.
     * The engine itself stops at [GENERATION_TIMEOUT_MS] (set at load), which bounds
     * how long this holds the engine — a blocking native call can't be interrupted
     * from Kotlin, so the limit has to live inside the engine.
     */
    suspend fun chat(system: String, user: String, maxTokens: Int): Output {
        require(available) { "本地推理库不可用(仅 arm64 设备支持)" }
        return mutex.withLock {
            require(handle != 0L) { "本地模型尚未加载" }
            val started = SystemClock.elapsedRealtime()
            val text = withContext(infra) { Tracing.section(Tracing.LLM_CHAT) { nativeChat(handle, system, user, maxTokens) } }.trim()
            // The engine doesn't report why it stopped; reaching the limit means it was cut.
            Output(text, timedOut = SystemClock.elapsedRealtime() - started >= GENERATION_TIMEOUT_MS)
        }
    }

    /** Free the model (e.g. when switching back to cloud to reclaim RAM). */
    suspend fun unload() = mutex.withLock {
        if (handle != 0L) { nativeRelease(handle); handle = 0L; loadedConfig = null }
    }

    /** Engine-side cap on one turn (prefill + decode). Same budget as a cloud reply. */
    const val GENERATION_TIMEOUT_MS = 30_000L

    private external fun nativeLoad(configPath: String, tmpDir: String, timeoutMs: Int): Long
    private external fun nativeChat(handle: Long, system: String, user: String, maxTokens: Int): String
    private external fun nativeRelease(handle: Long)
}
