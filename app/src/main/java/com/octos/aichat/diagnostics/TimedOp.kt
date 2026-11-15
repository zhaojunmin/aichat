package com.octos.aichat.diagnostics

/**
 * Operations whose duration is counted on users' phones (docs/observability-design.md
 * §5.4) — the tap path and learning only, never the per-event accessibility path.
 * Names are persisted: append new ones, never rename.
 */
enum class TimedOp(val zh: String, val en: String) {
    TAP_TO_RESULT("点击到出结果", "Tap → suggestions"),
    GENERATE_CLOUD("云端生成", "Cloud generation"),
    GENERATE_LOCAL("本地生成", "On-device generation"),
    OCR("截图识别", "Screenshot OCR"),
    PARSE("点击时解析", "Parse on tap"),
    LEARN("一次学习", "One learning pass"),
}

/**
 * Fixed-bucket duration counts. Buckets are upper bounds in ms; the last one is
 * open-ended. Percentiles are reported as "≤ bucket bound": coarse, but honest
 * about what fixed buckets can tell.
 */
object DurationBuckets {

    /** Upper bounds (ms) of every bucket but the last, which holds everything slower. */
    val BOUNDS_MS = longArrayOf(250, 500, 1_000, 2_000, 3_000, 5_000, 10_000, 30_000)

    /** Number of buckets: one per bound plus the open-ended last one. */
    val SIZE = BOUNDS_MS.size + 1

    fun indexOf(ms: Long): Int {
        val i = BOUNDS_MS.indexOfFirst { ms < it }
        return if (i < 0) BOUNDS_MS.size else i
    }

    /**
     * Index of the bucket holding the [p]-quantile (0 < p ≤ 1), or null with no samples.
     * E.g. p = 0.95 → the bucket where the cumulative count first reaches 95 %.
     */
    fun quantileBucket(counts: List<Int>, p: Double): Int? {
        val n = counts.sum()
        if (n == 0) return null
        val target = kotlin.math.ceil(p * n).toLong().coerceAtLeast(1)
        var cumulative = 0L
        counts.forEachIndexed { i, c ->
            cumulative += c
            if (cumulative >= target) return i
        }
        return counts.lastIndex
    }

    /** "≤ 2.0s" for a bounded bucket, "> 30s" for the open-ended one. */
    fun label(bucket: Int): String = if (bucket <
        BOUNDS_MS.size
    ) {
        "≤ ${seconds(BOUNDS_MS[bucket])}"
    } else {
        "> ${seconds(BOUNDS_MS.last())}"
    }

    /** Every bound is either under a second or a whole number of seconds. */
    private fun seconds(ms: Long): String = if (ms < 1_000) "${ms}ms" else "${ms / 1_000}s"
}
