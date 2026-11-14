package com.octos.aichat.diagnostics

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray

/** One past process exit as the system reports it. */
data class ExitRecord(val reason: String, val atMs: Long)

/**
 * The recording core behind [DiagLog], free of Android types so it is unit-tested
 * on the JVM. Hot-path contract (docs/observability-design.md §5.3): [record] only
 * touches memory — a queue append and a counter increment; files are written by
 * [flush], which the caller runs on a background thread.
 */
class DiagnosticsRecorder(
    private val store: DiagnosticsStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @Volatile var enabled: Boolean = false
        private set

    /** Called (on the recording thread) once enough events are pending; schedule a [flush]. */
    @Volatile var onPendingFull: (() -> Unit)? = null

    private val pending = ConcurrentLinkedQueue<DiagEvent>()
    private val pendingCount = AtomicInteger()
    private val counts = AtomicIntegerArray(ErrorCode.entries.size)
    private val durations = AtomicIntegerArray(TimedOp.entries.size * DurationBuckets.SIZE)
    @Volatile private var exitSeenAt = 0L
    private val fileLock = Any()

    /** Add the persisted counters (added, not set: events may already have been counted). */
    fun load() = synchronized(fileLock) {
        val s = store.readSummary()
        s.counts.forEach { (code, n) -> counts.addAndGet(code.ordinal, n) }
        s.durations.forEach { (op, buckets) ->
            buckets.forEachIndexed { i, n -> durations.addAndGet(op.ordinal * DurationBuckets.SIZE + i, n) }
        }
        exitSeenAt = maxOf(exitSeenAt, s.exitSeenAt)
    }

    fun record(
        code: ErrorCode,
        cause: Throwable? = null,
        ms: Long? = null,
        http: Int? = null,
        platform: Int? = null,
        pkg: String? = null,
    ) {
        if (!enabled) return
        add(
            DiagEvent(
                atMs = clock(),
                code = code,
                cause = cause?.let { DiagEvent.safeName(it.javaClass.simpleName) },
                ms = ms,
                http = http,
                platform = platform,
                // Package names reveal which apps someone uses: only kept where they
                // tell us which app's parsing broke (design §11.2).
                pkg = if (code.domain == ErrorCode.Domain.A11Y || code.domain == ErrorCode.Domain.OCR) DiagEvent.safeName(pkg) else null,
            )
        )
    }

    /** Count one completed [op] that took [ms]. Memory only, like [record]. */
    fun recordDuration(op: TimedOp, ms: Long) {
        if (!enabled || ms < 0) return
        durations.incrementAndGet(op.ordinal * DurationBuckets.SIZE + DurationBuckets.indexOf(ms))
    }

    /** The process is about to die: record and write synchronously, on the crashing thread. */
    fun recordCrash(cause: Throwable, thread: Thread) {
        if (!enabled) return
        add(
            DiagEvent(
                atMs = clock(),
                code = ErrorCode.APP_901,
                cause = DiagEvent.safeName(cause.javaClass.simpleName),
                thread = DiagEvent.safeName(thread.name),
            )
        )
        flush()
    }

    /** Record system-reported exits of earlier processes not yet recorded, ended after [sinceMs]. */
    fun recordExits(exits: List<ExitRecord>, sinceMs: Long) {
        if (!enabled) return
        val after = maxOf(sinceMs, exitSeenAt)
        exits.filter { it.atMs > after }.sortedBy { it.atMs }.forEach {
            add(DiagEvent(atMs = clock(), code = ErrorCode.APP_902, exit = DiagEvent.safeName(it.reason), exitAt = it.atMs))
            exitSeenAt = maxOf(exitSeenAt, it.atMs)
        }
    }

    /** Write pending events and counters to disk. Background thread (or the crashing one). */
    fun flush() = synchronized(fileLock) {
        if (!enabled) return
        val lines = ArrayList<String>()
        while (true) {
            val e = pending.poll() ?: break
            pendingCount.decrementAndGet()
            lines.add(e.toLine())
        }
        store.append(lines)
        store.writeSummary(DiagSummary(countsSnapshot(), exitSeenAt, durationsSnapshot()))
    }

    /** Start or stop recording. Takes effect at once; no IO. Turning off should be followed by [wipe]. */
    fun setEnabled(on: Boolean) {
        enabled = on
    }

    /** Delete everything kept, in memory and on disk (design §5.6). File IO: background thread. */
    fun wipe() = synchronized(fileLock) {
        pending.clear()
        pendingCount.set(0)
        for (i in 0 until counts.length()) counts.set(i, 0)
        for (i in 0 until durations.length()) durations.set(i, 0)
        exitSeenAt = 0L
        store.clear()
    }

    fun countsSnapshot(): Map<ErrorCode, Int> =
        ErrorCode.entries.associateWith { counts.get(it.ordinal) }.filterValues { it > 0 }

    /** Bucket counts per operation (only operations with at least one sample). */
    fun durationsSnapshot(): Map<TimedOp, List<Int>> =
        TimedOp.entries.associateWith { op ->
            List(DurationBuckets.SIZE) { i -> durations.get(op.ordinal * DurationBuckets.SIZE + i) }
        }.filterValues { it.any { c -> c > 0 } }

    /** The newest [n] events, including ones not yet flushed. */
    fun recent(n: Int): List<DiagEvent> {
        flush()
        return store.recent(n).mapNotNull(DiagEvent::parse)
    }

    private fun add(e: DiagEvent) {
        pending.add(e)
        counts.incrementAndGet(e.code.ordinal)
        if (pendingCount.incrementAndGet() >= FLUSH_AT) onPendingFull?.invoke()
    }

    companion object {
        /** Flush once this many events are pending (otherwise the periodic flush picks them up). */
        const val FLUSH_AT = 20
    }
}
