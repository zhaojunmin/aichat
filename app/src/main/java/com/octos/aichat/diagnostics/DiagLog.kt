package com.octos.aichat.diagnostics

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app's single entry point for diagnostics (docs/observability-design.md).
 *
 * Call sites pass an [ErrorCode] plus structured fields — there is deliberately no
 * parameter for free text, so chat content, keys or exception messages can't be
 * recorded. Until [install] runs (and while switched off) every call is a no-op.
 *
 * Cost: [record] is a volatile read when off, a queue append + counter increment
 * when on. Files are written on one background thread, every [FLUSH_SECONDS] or
 * when enough events pile up.
 */
/** Diagnostics kept at one moment: whether recording is on, the counters, and the stored events (oldest first). */
data class DiagSnapshot(
    val enabled: Boolean,
    val counts: Map<ErrorCode, Int>,
    val events: List<DiagEvent>,
    val durations: Map<TimedOp, List<Int>> = emptyMap(),
)

object DiagLog {

    @Volatile private var recorder: DiagnosticsRecorder? = null

    private val io: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "diagnostics").apply { isDaemon = true }
    }

    /** Debug builds: mirror each event to logcat. Null in release. */
    @Volatile var echo: ((String) -> Unit)? = null

    private val periodicFlush = AtomicBoolean(false)

    /** Set the recorder (once per process; tests may swap it). */
    fun install(r: DiagnosticsRecorder) {
        recorder = r
        r.onPendingFull = { io.execute(::flushQuietly) }
        io.execute { runCatching { r.load() } }
        if (periodicFlush.compareAndSet(false, true)) {
            io.scheduleWithFixedDelay(::flushQuietly, FLUSH_SECONDS, FLUSH_SECONDS, TimeUnit.SECONDS)
        }
    }

    fun record(
        code: ErrorCode,
        cause: Throwable? = null,
        ms: Long? = null,
        http: Int? = null,
        platform: Int? = null,
        pkg: String? = null,
    ) {
        val r = recorder ?: return
        if (!r.enabled) return
        r.record(code, cause, ms, http, platform, pkg)
        echo?.invoke("${code.id} ${cause?.javaClass?.simpleName.orEmpty()} ms=$ms http=$http platform=$platform pkg=$pkg")
    }

    /** Count one completed [op] that took [ms] (design §5.4: tap path and learning only). */
    fun recordDuration(op: TimedOp, ms: Long) {
        recorder?.recordDuration(op, ms)
    }

    /**
     * Run [block] and count its duration under [op] if it completes normally — a
     * failure is already recorded as an error code, and would skew the timings.
     */
    inline fun <T> timed(op: TimedOp, block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        recordDuration(op, (System.nanoTime() - start) / 1_000_000)
        return result
    }

    /** From the uncaught-exception handler: written synchronously before the process dies. */
    fun recordCrash(cause: Throwable, thread: Thread) {
        recorder?.recordCrash(cause, thread)
    }

    /** Switch on/off. Off stops recording at once; the stored data is deleted in the background. */
    fun setEnabled(on: Boolean) {
        val r = recorder ?: return
        r.setEnabled(on)
        if (!on) io.execute { runCatching { r.wipe() } }
    }

    /** User asked to clear the diagnostics (recording stays as it is). */
    fun clear() {
        val r = recorder ?: return
        io.execute { runCatching { r.wipe() } }
    }

    /** Record system-reported exits of earlier processes, read lazily on the background thread. */
    fun recordExits(sinceMs: Long, read: () -> List<ExitRecord>) {
        val r = recorder ?: return
        io.execute { runCatching { r.recordExits(read(), sinceMs) } }
    }

    /** What is kept right now, for the export. File IO: call off the main thread. Null before [install]. */
    fun snapshot(): DiagSnapshot? {
        val r = recorder ?: return null
        return DiagSnapshot(
            r.enabled, r.countsSnapshot(), if (r.enabled) r.recent(MAX_EVENTS) else emptyList(), r.durationsSnapshot(),
        )
    }

    private fun flushQuietly() {
        runCatching { recorder?.flush() }
    }

    const val FLUSH_SECONDS = 5L

    /** Upper bound read back for the export (two 256 KB files hold about this many). */
    private const val MAX_EVENTS = 5_000
}
