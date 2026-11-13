package com.octos.aichat.data.memory

import android.content.Context
import java.io.File

/**
 * Makes the bge embedding model available on disk and loads it into [EmbeddingEngine].
 * The `.mnn` + `vocab.txt` ship in `assets/embed/` and are copied to filesDir once
 * (MNN loads from a path). Everything is best-effort: if the assets aren't bundled
 * yet, or the native encoder isn't built, this returns false and recall stays lexical.
 */
object EmbeddingModelManager {

    private const val MODEL_ASSET = "embed/bge_fp16.mnn"
    private const val VOCAB_ASSET = "embed/vocab.txt"
    private const val MODEL_FILE = "bge_fp16.mnn"
    private const val VOCAB_FILE = "vocab.txt"

    fun ensureReady(ctx: Context): Boolean {
        if (EmbeddingEngine.isReady) return true
        val dir = File(ctx.filesDir, "embed").apply { mkdirs() }
        val model = File(dir, MODEL_FILE)
        val vocab = File(dir, VOCAB_FILE)
        if (!copyAssetIfMissing(ctx, MODEL_ASSET, model)) return false
        if (!copyAssetIfMissing(ctx, VOCAB_ASSET, vocab)) return false
        return EmbeddingEngine.ensureLoaded(model.absolutePath, vocab.absolutePath)
    }

    private fun copyAssetIfMissing(ctx: Context, asset: String, dest: File): Boolean {
        if (dest.exists() && dest.length() > 0) return true
        return runCatching {
            ctx.assets.open(asset).use { input -> dest.outputStream().use { input.copyTo(it) } }
            true
        }.getOrDefault(false)
    }
}
