package com.octos.aichat.diagnostics

import java.io.File

/**
 * Persisted counters: how often each code happened, the newest exit already recorded,
 * and per-operation duration bucket counts ([DurationBuckets]).
 */
data class DiagSummary(
    val counts: Map<ErrorCode, Int> = emptyMap(),
    val exitSeenAt: Long = 0L,
    val durations: Map<TimedOp, List<Int>> = emptyMap(),
)

/**
 * The files under `files/diagnostics/`: an append-only event log rotated by size
 * (current + one previous file, [maxBytes] each — about 4000 events in all) and a
 * small summary file. Plain java.io, no Android types. Calls are serialised.
 */
class DiagnosticsStore(private val dir: File, private val maxBytes: Long = 256L * 1024) {

    private val current = File(dir, "events.log")
    private val previous = File(dir, "events.1.log")
    private val summary = File(dir, "summary")

    @Synchronized
    fun append(lines: List<String>) {
        if (lines.isEmpty()) return
        dir.mkdirs()
        if (current.length() >= maxBytes) {
            previous.delete()
            current.renameTo(previous)
        }
        current.appendText(lines.joinToString(separator = "\n", postfix = "\n"))
    }

    /** The newest [n] stored lines, oldest first. */
    @Synchronized
    fun recent(n: Int): List<String> = (readLines(previous) + readLines(current)).takeLast(n)

    @Synchronized
    fun readSummary(): DiagSummary {
        if (!summary.exists()) return DiagSummary()
        var exitSeenAt = 0L
        val counts = HashMap<ErrorCode, Int>()
        val durations = HashMap<TimedOp, List<Int>>()
        for (line in summary.readLines()) {
            val (key, value) = line.split('\t').takeIf { it.size == 2 } ?: continue
            when {
                key == EXIT_SEEN_KEY -> exitSeenAt = value.toLongOrNull() ?: 0L

                key.startsWith(DURATION_PREFIX) -> {
                    val op =
                        TimedOp.entries.firstOrNull { it.name == key.removePrefix(DURATION_PREFIX) }
                            ?: continue
                    val buckets = value.split(',').map { it.toIntOrNull() ?: return@map null }
                    if (buckets.size == DurationBuckets.SIZE &&
                        buckets.all { it != null }
                    ) {
                        durations[op] = buckets.filterNotNull()
                    }
                }

                else -> {
                    val code = ErrorCode.of(key) ?: continue
                    counts[code] = value.toIntOrNull() ?: continue
                }
            }
        }
        return DiagSummary(counts, exitSeenAt, durations)
    }

    @Synchronized
    fun writeSummary(s: DiagSummary) {
        dir.mkdirs()
        val text = buildString {
            append(EXIT_SEEN_KEY).append('\t').append(s.exitSeenAt).append('\n')
            s.counts.filterValues {
                it > 0
            }.forEach { (code, n) -> append(code.id).append('\t').append(n).append('\n') }
            s.durations.filterValues { it.any { c -> c > 0 } }.forEach { (op, buckets) ->
                append(
                    DURATION_PREFIX,
                ).append(op.name).append('\t').append(buckets.joinToString(",")).append('\n')
            }
        }
        // Write-then-rename so a crash mid-write can't leave a half summary behind.
        val tmp = File(dir, "summary.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(summary)) {
            summary.delete()
            tmp.renameTo(summary)
        }
    }

    /** Delete everything kept (diagnostics switched off, or "clear"). */
    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun readLines(f: File): List<String> =
        if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()

    private companion object {
        const val EXIT_SEEN_KEY = "exitSeenAt"
        const val DURATION_PREFIX = "T:"
    }
}
