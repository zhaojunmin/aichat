package com.octos.aichat.data.local

/**
 * Selectable on-device models (MNN-converted Qwen2.5, hosted by taobao-mnn).
 * Smaller = faster but lower quality. Files are discovered at download time from
 * the HF tree API, so no per-model file list is hard-coded here.
 */
enum class LocalModel(
    val id: String,
    val displayName: String,
    val hint: String,
    val repo: String,
    val approxMB: Int,
) {
    // Hosted on ModelScope (reachable in mainland China; HuggingFace is not).
    QWEN_0_5B("qwen0_5b", "0.5B", "最快·质量一般", "MNN/Qwen2.5-0.5B-Instruct-MNN", 540),
    QWEN_1_5B("qwen1_5b", "1.5B", "均衡", "MNN/Qwen2.5-1.5B-Instruct-MNN", 830),
    QWEN_3B("qwen3b", "3B", "最佳·较慢", "MNN/Qwen2.5-3B-Instruct-MNN", 2300);

    companion object {
        val DEFAULT = QWEN_1_5B
        fun of(id: String?): LocalModel = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
