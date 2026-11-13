package com.octos.aichat.ai.embedding

import android.content.Context

/**
 * The minimal on-device embedding capability the memory layer depends on (DIP):
 * prepare the model, turn text into vectors, and (de)serialize them for BLOB
 * storage. The concrete MNN-backed engine, model manager and codec stay behind
 * this facade, so the memory package needs only this one type — obtain the
 * shipping implementation via [Embedder.onDevice].
 */
interface Embedder {

    /** True once the model + native encoder are loaded and callable. */
    val isReady: Boolean

    /** Pooling/model version; stored vectors from an older version are recomputed. */
    val version: Int

    /** Best-effort: ensure the model is on disk and loaded. Returns current readiness. */
    fun prepare(context: Context): Boolean

    /** Embed a passage (e.g. a stored memory). Null when not ready / on error. */
    fun embed(text: String): FloatArray?

    /** Embed a retrieval query (bge instruction prefix applied). Null when not ready. */
    fun embedQuery(text: String): FloatArray?

    /** Serialize a vector for the memory BLOB column, and read it back. */
    fun encode(vector: FloatArray): ByteArray
    fun decode(bytes: ByteArray): FloatArray

    companion object {
        /** The shipping on-device implementation (bge-small-zh via MNN). */
        val onDevice: Embedder get() = MnnEmbedder
    }
}

/**
 * Facade binding the [Embedder] contract to the concrete on-device parts:
 * [ModelAssets] (model files), [Encoder] (native encoder) and
 * [VectorCodec] (vector ⇄ bytes). File-private so nothing outside this package
 * can reach the concrete classes through it.
 */
private object MnnEmbedder : Embedder {
    override val isReady: Boolean get() = Encoder.isReady
    override val version: Int get() = Encoder.VERSION
    override fun prepare(context: Context): Boolean = ModelAssets.ensureReady(context)
    override fun embed(text: String): FloatArray? = Encoder.embed(text)
    override fun embedQuery(text: String): FloatArray? = Encoder.embedQuery(text)
    override fun encode(vector: FloatArray): ByteArray = VectorCodec.encode(vector)
    override fun decode(bytes: ByteArray): FloatArray = VectorCodec.decode(bytes)
}
