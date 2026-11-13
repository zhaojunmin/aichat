package com.octos.aichat.data.memory

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 512×float32 little-endian ⇄ FloatArray, for storing vectors in the memory BLOB column. */
object EmbeddingCodec {
    fun encode(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().put(v)
        return buf.array()
    }

    fun decode(bytes: ByteArray): FloatArray {
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }
}

/**
 * On-device sentence embedding (bge-small-zh-v1.5, MNN fp16) via the shared MNN
 * native libs. Tokenization (WordPiece) is done in Kotlin; the JNI只收数字张量,
 * 跑 encoder 返回 CLS 池化 + L2 归一的 512 维向量。
 *
 * **Guarded**: [isReady] is false until [ensureLoaded] succeeds, which only happens
 * once the `.mnn` model + `vocab.txt` are present AND the encoder JNI symbols exist
 * (added in a later native build). Until then every call no-ops/returns null and the
 * retrieval layer falls back to lexical — so this compiles and ships inertly now.
 *
 * JNI 符号须匹配 FQN:`Java_com_octos_aichat_data_memory_EmbeddingEngine_nativeEmbed*`。
 */
object EmbeddingEngine {

    const val DIM = 512

    /** Model/pooling口径版本;改模型或池化口径时 +1 以触发全量重算。 */
    const val VERSION = 1

    private const val QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章:"
    private const val MAX_TOKENS = 256

    private val lock = Any()
    @Volatile private var libsLoaded = false
    @Volatile private var handle = 0L
    @Volatile private var tokenizer: WordPieceTokenizer? = null

    /** Diagnostic breadcrumb for the last load/embed attempt (shown in dev UI). */
    @Volatile var status: String = "未初始化"
        internal set

    /** True only after a model is loaded and the native encoder is callable. */
    val isReady: Boolean get() = handle != 0L && tokenizer != null

    private fun loadLibs(): Boolean = synchronized(lock) {
        if (libsLoaded) return true
        libsLoaded = runCatching {
            System.loadLibrary("MNN")
            System.loadLibrary("MNN_Express")
            System.loadLibrary("embedjni") // encoder JNI (bge via MNN Interpreter)
            true
        }.getOrElse { status = "lib载入失败: ${it.message}"; false }
        libsLoaded
    }

    /**
     * Load the bge `.mnn` model and `vocab.txt`. Idempotent. Returns false (leaving
     * [isReady] false → lexical fallback) if libs/model/vocab/native symbols are missing.
     */
    fun ensureLoaded(modelPath: String, vocabPath: String): Boolean = synchronized(lock) {
        if (isReady) return true
        if (!loadLibs()) return false
        val tk = tokenizer ?: runCatching { WordPieceTokenizer.fromFile(vocabPath) }
            .getOrElse { status = "词表载入失败: ${it.message}"; return false }
        val h = runCatching { nativeEmbedLoad(modelPath) }
            .getOrElse { status = "native异常: ${it.message}"; 0L }
        if (h == 0L) { if (!status.startsWith("native")) status = "模型加载返回0(createFromFile/session 失败)"; return false }
        tokenizer = tk
        handle = h
        status = "就绪"
        true
    }

    /** Embed a retrieval query (adds the bge query instruction prefix). */
    fun embedQuery(text: String): FloatArray? = embed(QUERY_PREFIX + text)

    /** Embed a passage/memory (no prefix). Returns null when not ready or on error. */
    fun embed(text: String): FloatArray? = synchronized(lock) {
        if (!isReady) return null
        val tk = tokenizer ?: return null
        val (ids, mask) = tk.encode(text, MAX_TOKENS)
        runCatching { nativeEmbed(handle, ids, mask) }
            .getOrElse { status = "embed异常: ${it.message}"; null }
    }

    fun release() = synchronized(lock) {
        if (handle != 0L) { runCatching { nativeEmbedRelease(handle) }; handle = 0L }
    }

    // --- native (bound in the encoder JNI build; see embedding-integration-design.md §3) ---
    private external fun nativeEmbedLoad(modelPath: String): Long
    private external fun nativeEmbed(handle: Long, ids: IntArray, mask: IntArray): FloatArray
    private external fun nativeEmbedRelease(handle: Long)
}
