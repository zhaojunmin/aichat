package com.octos.aichat.data.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Owns the on-device model files (MNN-format Qwen2.5). Models are not shipped in
 * the APK — the chosen one is downloaded on demand into the app's private files
 * dir, one directory per model so switching sizes keeps both.
 */
object LocalModelManager {

    // Docs / metadata we don't need at runtime.
    private val SKIP = setOf(
        ".gitattributes", "README.md", "LICENSE",
        "configuration.json", "export_args.json", "refresh_metadata.json",
    )

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun dir(ctx: Context, model: LocalModel): File = File(ctx.filesDir, "llm/${model.id}")
    fun configPath(ctx: Context, model: LocalModel): String =
        File(dir(ctx, model), "config.json").absolutePath
    fun tmpDir(ctx: Context): String =
        File(ctx.cacheDir, "llm-tmp").apply { mkdirs() }.absolutePath

    /**
     * Ready when the config and the weight file are present. Weights are streamed
     * to a `.part` file and renamed only on completion, so a final weight file
     * existing means the download finished.
     */
    fun isReady(ctx: Context, model: LocalModel): Boolean {
        val d = dir(ctx, model)
        return File(d, "config.json").exists() && File(d, "llm.mnn.weight").exists()
    }

    data class Progress(
        val file: String,
        val index: Int,
        val total: Int,
        val doneBytes: Long,
        val totalBytes: Long,
    ) {
        val percent: Int get() = if (totalBytes > 0) ((doneBytes * 100) / totalBytes).toInt() else 0
    }

    /**
     * Download every model file (resumable per-file via `.part`). Reports an
     * overall byte percentage. Returns a failure (keeping partial files) so the
     * caller can show the error and retry; completed files are skipped next time.
     */
    suspend fun download(
        ctx: Context,
        model: LocalModel,
        onProgress: (Progress) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val files = treeFiles(model.repo)
            require(files.isNotEmpty()) { "未获取到模型文件清单" }
            val totalBytes = files.sumOf { it.second }
            val d = dir(ctx, model).apply { mkdirs() }
            var done = 0L
            files.forEachIndexed { i, (name, size) ->
                val dest = File(d, name)
                if (dest.exists() && dest.length() > 0) {
                    done += size
                    onProgress(Progress(name, i + 1, files.size, done, totalBytes))
                    return@forEachIndexed
                }
                val part = File(d, "$name.part")
                val req = Request.Builder()
                    .url("https://modelscope.cn/models/${model.repo}/resolve/master/$name")
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("下载失败 $name:HTTP ${resp.code}")
                    val body = resp.body ?: error("空响应:$name")
                    part.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                onProgress(Progress(name, i + 1, files.size, done, totalBytes))
                            }
                        }
                    }
                }
                check(part.renameTo(dest)) { "写入失败:$name" }
            }
        }
    }

    /** Discover downloadable files + sizes from the ModelScope files API. */
    private fun treeFiles(repo: String): List<Pair<String, Long>> {
        val req = Request.Builder()
            .url("https://modelscope.cn/api/v1/models/$repo/repo/files?Revision=master")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("无法获取文件清单:HTTP ${resp.code}")
            val files = JSONObject(resp.body?.string().orEmpty())
                .optJSONObject("Data")?.optJSONArray("Files") ?: error("文件清单为空")
            val out = ArrayList<Pair<String, Long>>()
            for (i in 0 until files.length()) {
                val o = files.optJSONObject(i) ?: continue
                if (o.optString("Type") != "blob") continue
                val path = o.optString("Path")
                if (path.isEmpty() || path in SKIP || path.endsWith(".md")) continue
                out.add(path to o.optLong("Size", 0L))
            }
            return out
        }
    }

    /** Remove a downloaded model to reclaim storage. */
    fun delete(ctx: Context, model: LocalModel) {
        dir(ctx, model).deleteRecursively()
    }
}
