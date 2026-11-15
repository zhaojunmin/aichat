package com.octos.aichat.diagnostics

/**
 * One recorded event. Every field is a number, an enum name, a class name or a
 * package name — never free text — so nothing a user typed or read can end up here.
 * Stored one per line: `epochMs<TAB>id<TAB>LEVEL<TAB>key=value key=value…`.
 */
data class DiagEvent(
    val atMs: Long,
    val code: ErrorCode,
    /** Simple class name of the exception; its message is never read. */
    val cause: String? = null,
    val thread: String? = null,
    val ms: Long? = null,
    val http: Int? = null,
    /** A platform error code (e.g. the screenshot failure reason). */
    val platform: Int? = null,
    /** Target app package — only kept for A11Y / OCR codes (see [DiagnosticsRecorder]). */
    val pkg: String? = null,
    /** For APP-902: the system's exit reason name and when that process ended. */
    val exit: String? = null,
    val exitAt: Long? = null,
) {
    fun toLine(): String = buildString {
        append(atMs).append('\t').append(code.id).append('\t').append(code.level.name).append('\t')
        val fields = listOfNotNull(
            cause?.let { "cause=$it" },
            thread?.let { "thread=$it" },
            ms?.let { "ms=$it" },
            http?.let { "http=$it" },
            platform?.let { "platform=$it" },
            pkg?.let { "pkg=$it" },
            exit?.let { "exit=$it" },
            exitAt?.let { "exitAt=$it" },
        )
        append(fields.joinToString(" "))
    }

    companion object {
        /** Parses a stored line; null for anything malformed or from an unknown code. */
        fun parse(line: String): DiagEvent? {
            val parts = line.split('\t')
            if (parts.size < 3) return null
            val at = parts[0].toLongOrNull() ?: return null
            val code = ErrorCode.of(parts[1]) ?: return null
            val f = parts.getOrNull(3).orEmpty().split(' ').filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            return DiagEvent(
                atMs = at,
                code = code,
                cause = f["cause"],
                thread = f["thread"],
                ms = f["ms"]?.toLongOrNull(),
                http = f["http"]?.toIntOrNull(),
                platform = f["platform"]?.toIntOrNull(),
                pkg = f["pkg"],
                exit = f["exit"],
                exitAt = f["exitAt"]?.toLongOrNull(),
            )
        }

        private val SAFE_NAME = Regex("""[A-Za-z0-9_$.\-]{1,100}""")

        /**
         * Identifier-like values only (class / thread / package / enum names); anything else is
         * dropped.
         */
        fun safeName(value: String?): String? = value?.takeIf { SAFE_NAME.matches(it) }
    }
}
