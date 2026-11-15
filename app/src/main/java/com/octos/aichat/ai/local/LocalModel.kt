package com.octos.aichat.ai.local

import androidx.annotation.StringRes
import com.octos.aichat.R
import com.octos.aichat.data.AppSettings

/**
 * Selectable on-device models (MNN-converted Qwen2.5, hosted by taobao-mnn).
 * Smaller = faster but lower quality. Files are discovered at download time from
 * the HF tree API, so no per-model file list is hard-coded here.
 */
enum class LocalModel(
    val id: String,
    val displayName: String,
    @StringRes val hintRes: Int,
    val repo: String,
    val approxMb: Int,
) {
    // Hosted on ModelScope (reachable in mainland China; HuggingFace is not).
    QWEN_0_5B("qwen0_5b", "0.5B", R.string.model_hint_fast, "MNN/Qwen2.5-0.5B-Instruct-MNN", 540),
    QWEN_1_5B(
        "qwen1_5b",
        "1.5B",
        R.string.model_hint_balanced,
        "MNN/Qwen2.5-1.5B-Instruct-MNN",
        830,
    ),
    QWEN_3B("qwen3b", "3B", R.string.model_hint_best, "MNN/Qwen2.5-3B-Instruct-MNN", 2300),
    ;

    companion object {
        val DEFAULT: LocalModel = entries.first { it.id == AppSettings.DEFAULT_LOCAL_MODEL_ID }
        fun of(id: String?): LocalModel = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** The selected on-device model. Here rather than on [AppSettings]: settings don't know the AI layer. */
val AppSettings.localModel: LocalModel get() = LocalModel.of(localModelId)
