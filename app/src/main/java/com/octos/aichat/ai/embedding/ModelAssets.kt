package com.octos.aichat.ai.embedding

import android.content.Context
import com.octos.aichat.diagnostics.DiagLog
import com.octos.aichat.diagnostics.ErrorCode
import java.io.File

/**
 * Makes the bge embedding model available on disk and loads it into [Encoder].
 * The `.mnn` + `vocab.txt` ship in `assets/embed/` and are copied to filesDir once
 * (MNN loads from a path). Everything is best-effort: if the assets aren't bundled
 * yet, or the native encoder isn't built, this returns false and recall stays lexical.
 */
object ModelAssets {

    private const val MODEL_ASSET = "embed/bge_fp16.mnn"
    private const val VOCAB_ASSET = "embed/vocab.txt"
    private const val MODEL_FILE = "bge_fp16.mnn"
    private const val VOCAB_FILE = "vocab.txt"

    fun ensureReady(ctx: Context): Boolean {
        if (Encoder.isReady) return true
        val dir = File(ctx.filesDir, "embed").apply { mkdirs() }
        val model = File(dir, MODEL_FILE)
        val vocab = File(dir, VOCAB_FILE)
        if (!copyAssetIfMissing(ctx, MODEL_ASSET, model)) return Encoder.failed(ErrorCode.EMB_702)
        if (!copyAssetIfMissing(ctx, VOCAB_ASSET, vocab)) return Encoder.failed(ErrorCode.EMB_702)
        return Encoder.ensureLoaded(model.absolutePath, vocab.absolutePath)
    }

    private fun copyAssetIfMissing(ctx: Context, asset: String, dest: File): Boolean {
        if (dest.exists() && dest.length() > 0) return true
        return runCatching {
            ctx.assets.open(asset).use { input -> dest.outputStream().use { input.copyTo(it) } }
            true
        }.getOrElse {
            DiagLog.record(ErrorCode.EMB_702, cause = it)
            false
        }
    }
}
