package com.octos.aichat.diagnostics

import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

/**
 * Named trace sections on the latency-critical paths, for measuring them with
 * atrace/Perfetto on a release build (see docs/performance-testing.md).
 *
 * Cost when nobody is tracing: one cached "is the app tag enabled" check per
 * call — no logging, no allocation. Names are constants so hot paths never
 * build strings.
 */
object Tracing {

    // Accessibility. onEvent: main thread, every event (budget P4 ≤ 8 ms);
    // parse: the on-demand tree walk when the bubble is tapped (IO thread).
    const val A11Y_EVENT = "a11y.onEvent"
    const val A11Y_PARSE = "a11y.parse"

    // Bubble tap → suggestions shown (budget P1) and its stages.
    const val BUBBLE_TAP_TO_RESULT = "bubble.tapToResult"
    const val BUBBLE_RESOLVE_CONVERSATION = "bubble.resolveConversation"
    const val BUBBLE_OCR = "bubble.ocr"
    const val BUBBLE_MEMORY_BLOCK = "bubble.memoryBlock"
    const val BUBBLE_GENERATE = "bubble.generate"

    // On-device LLM: one blocking native inference turn.
    const val LLM_CHAT = "llm.chat"

    private val cookies = AtomicInteger()

    /** Synchronous section: begins and ends on the calling thread. */
    inline fun <T> section(name: String, block: () -> T): T {
        Trace.beginSection(name)
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }

    /**
     * Section around code that may suspend and resume on another thread, where a
     * thread-bound [section] would come out unbalanced. Uses async trace events
     * (API 29+); a no-op wrapper on older devices.
     */
    inline fun <T> asyncSection(name: String, block: () -> T): T {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return block()
        val cookie = nextCookie()
        Trace.beginAsyncSection(name, cookie)
        try {
            return block()
        } finally {
            Trace.endAsyncSection(name, cookie)
        }
    }

    @PublishedApi internal fun nextCookie(): Int = cookies.incrementAndGet()
}
