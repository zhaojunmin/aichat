package com.octos.aichat.data.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Owns the on-device model files (Qwen2.5-3B in MNN format). The ~2.3GB model is
 * not shipped in the APK — it is downloaded on first enabling of local mode and
 * stored under the app's private files dir.
 */
object LocalModelManager {

    // taobao-mnn hosts ready-to-run MNN models; resolve/main streams the raw file.
    private const val BASE =
        "https://huggingface.co/taobao-mnn/Qwen2.5-3B-Instruct-MNN/resolve/main"

    // Download smallest-first so a broken run fails fast before the 1.7GB weight.
    private val FILES = listOf(
        "config.json", "llm_config.json", "llm.mnn.json", "tokenizer.txt",
        "llm.mnn", "embeddings_bf16.bin", "llm.mnn.weight",
    )

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun dir(ctx: Context): File = File(ctx.filesDir, "llm/qwen2.5-3b")
    fun configPath(ctx: Context): String = File(dir(ctx), "config.json").absolutePath
    fun tmpDir(ctx: Context): String =
        File(ctx.cacheDir, "llm-tmp").apply { mkdirs() }.absolutePath

    /** True once every file is present and the weight looks fully downloaded. */
    fun isReady(ctx: Context): Boolean {
        val d = dir(ctx)
        val weight = File(d, "llm.mnn.weight")
        return File(d, "config.json").exists() &&
            File(d, "tokenizer.txt").exists() &&
            File(d, "embeddings_bf16.bin").exists() &&
            weight.exists() && weight.length() > 1_000_000_000L
    }

    data class Progress(val file: String, val index: Int, val total: Int, val fileBytes: Long)

    /**
     * Download all model files (resumable per-file via a .part suffix). Calls
     * [onProgress] as bytes arrive. Returns a failure (keeping partial files) so
     * the caller can retry; completed files are skipped on the next run.
     */
    suspend fun download(ctx: Context, onProgress: (Progress) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val d = dir(ctx).apply { mkdirs() }
                FILES.forEachIndexed { i, name ->
                    val dest = File(d, name)
                    if (dest.exists() && dest.length() > 0) return@forEachIndexed
                    val part = File(d, "$name.part")
                    val req = Request.Builder().url("$BASE/$name").build()
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) error("下载失败 $name: HTTP ${resp.code}")
                        val body = resp.body ?: error("空响应 $name")
                        part.outputStream().use { out ->
                            body.byteStream().use { input ->
                                val buf = ByteArray(1 shl 16)
                                var total = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    total += n
                                    onProgress(Progress(name, i + 1, FILES.size, total))
                                }
                            }
                        }
                        part.renameTo(dest)
                    }
                }
            }
        }

    /** Remove the downloaded model to reclaim storage. */
    fun delete(ctx: Context) {
        dir(ctx).deleteRecursively()
    }
}
