package com.octos.aichat.ai.local

import android.content.Context
import com.octos.aichat.R
import com.octos.aichat.diagnostics.DiagLog
import com.octos.aichat.diagnostics.ErrorCode
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Owns the on-device model files (MNN-format Qwen2.5). Models are not shipped in
 * the APK — the chosen one is downloaded on demand into the app's private files
 * dir, one directory per model so switching sizes keeps both.
 */
object ModelStore {

    // Docs / metadata we don't need at runtime.
    private val SKIP = setOf(
        ".gitattributes",
        "README.md",
        "LICENSE",
        "configuration.json",
        "export_args.json",
        "refresh_metadata.json",
    )

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // The app's files and cache dirs, remembered once at startup ([init]) so the engine can
    // find a model without a Context being passed through the AI layer. Paths only.
    @Volatile private var filesRoot: File? = null

    @Volatile private var cacheRoot: File? = null

    /** Remember where the app keeps its files. Keeps the paths only, never [ctx]. */
    fun init(ctx: Context) {
        filesRoot = ctx.filesDir
        cacheRoot = ctx.cacheDir
    }

    fun dir(ctx: Context, model: LocalModel): File = dirIn(ctx.filesDir, model)
    fun configPath(ctx: Context, model: LocalModel): String = configIn(ctx.filesDir, model)
    fun tmpDir(ctx: Context): String = tmpIn(ctx.cacheDir)

    /**
     * Ready when the config and the weight file are present. Weights are streamed
     * to a `.part` file and renamed only on completion, so a final weight file
     * existing means the download finished.
     */
    fun isReady(ctx: Context, model: LocalModel): Boolean = isComplete(dirIn(ctx.filesDir, model))

    /** [isReady] against the remembered dirs; false before [init]. */
    internal fun isReady(model: LocalModel): Boolean =
        filesRoot?.let { isComplete(dirIn(it, model)) } == true

    /** Config path and engine scratch dir from the remembered dirs; null before [init]. */
    internal fun loadPaths(model: LocalModel): Pair<String, String>? {
        val files = filesRoot ?: return null
        val cache = cacheRoot ?: return null
        return configIn(files, model) to tmpIn(cache)
    }

    private fun dirIn(filesRoot: File, model: LocalModel) = File(filesRoot, "llm/${model.id}")
    private fun configIn(filesRoot: File, model: LocalModel) =
        File(dirIn(filesRoot, model), "config.json").absolutePath
    private fun tmpIn(cacheRoot: File) = File(cacheRoot, "llm-tmp").apply { mkdirs() }.absolutePath
    private fun isComplete(d: File) =
        File(d, "config.json").exists() && File(d, "llm.mnn.weight").exists()

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
            val files = treeFiles(ctx, model.repo)
            require(files.isNotEmpty()) { ctx.getString(R.string.ms_no_manifest) }
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
                    if (!resp.isSuccessful) {
                        error(
                            ctx.getString(R.string.ms_download_http, name, resp.code),
                        )
                    }
                    val body = resp.body ?: error(ctx.getString(R.string.ms_empty_resp, name))
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
                check(part.renameTo(dest)) { ctx.getString(R.string.ms_write_failed, name) }
            }
        }.onFailure {
            if (it !is CancellationException) DiagLog.record(ErrorCode.LLM_204, cause = it)
        }
    }

    /** Discover downloadable files + sizes from the ModelScope files API. */
    private fun treeFiles(ctx: Context, repo: String): List<Pair<String, Long>> {
        val req = Request.Builder()
            .url("https://modelscope.cn/api/v1/models/$repo/repo/files?Revision=master")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error(ctx.getString(R.string.ms_manifest_http, resp.code))
            val files = JSONObject(resp.body?.string().orEmpty())
                .optJSONObject("Data")?.optJSONArray("Files")
                ?: error(ctx.getString(R.string.ms_manifest_empty))
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
