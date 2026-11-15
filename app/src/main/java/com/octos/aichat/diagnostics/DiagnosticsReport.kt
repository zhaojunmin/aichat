package com.octos.aichat.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** App + device facts for the report header. */
data class DeviceInfo(
    val appVersion: String,
    val versionCode: Long,
    val buildType: String,
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdk: Int,
    val abi: String,
    val locale: String,
)

/** Everything the report is made from; assembled by the UI layer. */
data class ReportInput(
    val generatedAtMs: Long,
    val device: DeviceInfo,
    /** Label → value. Values must be enum names / booleans / ids; anything else is dropped. */
    val settings: List<Pair<String, String>>,
    val permissions: List<Pair<String, Boolean>>,
    val counts: Map<ErrorCode, Int>,
    /** Stored events, oldest first. */
    val events: List<DiagEvent>,
    /** Duration bucket counts per operation ([DurationBuckets]). */
    val durations: Map<TimedOp, List<Int>> = emptyMap(),
)

/**
 * Renders the plain-text diagnostics export (docs/observability-design.md §6).
 * Pure: everything comes from [ReportInput]; descriptions come from [ErrorCode]'s
 * fixed text, never from runtime data.
 */
object DiagnosticsReport {

    const val RECENT_EVENTS = 200
    const val RECENT_EXITS = 5

    fun render(input: ReportInput, chinese: Boolean, zone: TimeZone = TimeZone.getDefault()): String {
        val t = if (chinese) ZH else EN
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = zone }
        fun at(ms: Long) = time.format(Date(ms))
        fun ErrorCode.text() = if (chinese) zh else en
        val d = input.device

        return buildString {
            appendLine("== ${t.title} ==")
            appendLine("${t.generated}  ${at(input.generatedAtMs)}")
            appendLine("App       ${d.appVersion} (${d.versionCode}) ${d.buildType}")
            appendLine("${t.device}  ${d.manufacturer} ${d.model} · Android ${d.androidRelease} (API ${d.sdk}) · ${d.abi} · ${d.locale}")

            appendLine().appendLine("== ${t.settings} ==")
            appendLine(
                input.settings
                    .mapNotNull { (k, v) -> DiagEvent.safeName(v)?.let { "$k $it" } }
                    .joinToString(" · ")
                    .ifEmpty { "-" }
            )

            appendLine().appendLine("== ${t.permissions} ==")
            appendLine(input.permissions.joinToString(" · ") { (k, on) -> "$k ${if (on) t.yes else t.no}" }.ifEmpty { "-" })

            appendLine().appendLine("== ${t.exits} ==")
            val exits = input.events.filter { it.code == ErrorCode.APP_902 && it.exit != null }.takeLast(RECENT_EXITS)
            if (exits.isEmpty()) appendLine(t.none)
            exits.reversed().forEach { appendLine("${it.exitAt?.let(::at) ?: "?"}  ${it.exit}") }

            appendLine().appendLine("== ${t.counts} ==")
            val counts = input.counts.filterValues { it > 0 }.entries.sortedWith(
                compareByDescending<Map.Entry<ErrorCode, Int>> { it.value }.thenBy { it.key.id }
            )
            if (counts.isEmpty()) appendLine(t.none)
            counts.forEach { (code, n) -> appendLine("${code.id.padEnd(9)} ${n.toString().padStart(5)}  ${code.text()}") }

            appendLine().appendLine("== ${t.timings} ==")
            val timed = TimedOp.entries.mapNotNull { op -> input.durations[op]?.takeIf { it.sum() > 0 }?.let { op to it } }
            if (timed.isEmpty()) appendLine(t.none)
            timed.forEach { (op, buckets) ->
                val p50 = DurationBuckets.quantileBucket(buckets, 0.50)!!
                val p95 = DurationBuckets.quantileBucket(buckets, 0.95)!!
                appendLine("${if (chinese) op.zh else op.en}  n=${buckets.sum()}  P50 ${DurationBuckets.label(p50)}  P95 ${DurationBuckets.label(p95)}")
            }

            appendLine().appendLine("== ${t.recent} ==")
            val recent = input.events.takeLast(RECENT_EVENTS)
            if (recent.isEmpty()) appendLine(t.none)
            recent.forEach { e ->
                val fields = e.toLine().substringAfterLast('\t')
                append(at(e.atMs)).append("  ").append(e.code.id).append(' ').append(e.code.level.name)
                if (fields.isNotEmpty()) append("  ").append(fields)
                append("  ").appendLine(e.code.text())
            }
        }
    }

    private class Labels(
        val title: String, val generated: String, val device: String, val settings: String,
        val permissions: String, val exits: String, val counts: String, val timings: String, val recent: String,
        val none: String, val yes: String, val no: String,
    )

    private val ZH = Labels(
        title = "聊天助手 诊断信息", generated = "生成时间", device = "设备    ", settings = "设置",
        permissions = "权限", exits = "上次进程退出原因(最近 $RECENT_EXITS 次)",
        counts = "错误计数(自开启记录以来)", timings = "耗时(自开启记录以来)", recent = "最近事件(最多 $RECENT_EVENTS 条)",
        none = "(无)", yes = "是", no = "否",
    )

    private val EN = Labels(
        title = "Chat Assistant diagnostics", generated = "Generated", device = "Device   ", settings = "Settings",
        permissions = "Permissions", exits = "How earlier processes exited (last $RECENT_EXITS)",
        counts = "Error counts (since recording started)", timings = "Timings (since recording started)", recent = "Recent events (up to $RECENT_EVENTS)",
        none = "(none)", yes = "yes", no = "no",
    )
}
