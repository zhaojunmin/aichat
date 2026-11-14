package com.octos.aichat.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorCodeTest {

    @Test fun idsAreWellFormedUniqueAndMatchTheirDomain() {
        val format = Regex("""^(AI|LLM|A11Y|OCR|BUB|MEM|EMB|SET|APP)-(\d)\d\d$""")
        for (code in ErrorCode.entries) {
            val m = format.matchEntire(code.id) ?: throw AssertionError("bad id ${code.id}")
            assertEquals("${code.id}: hundreds digit vs domain", code.domain.hundreds, m.groupValues[2].toInt())
            assertEquals("${code.id}: enum name", code.id.replace('-', '_'), code.name)
            assertTrue("${code.id}: descriptions", code.zh.isNotBlank() && code.en.isNotBlank())
        }
        assertEquals(ErrorCode.entries.size, ErrorCode.entries.map { it.id }.toSet().size)
    }

    /**
     * A released code never changes meaning or level, and is never removed. New codes
     * must be appended to the snapshot (src/test/resources/diagnostics/error-codes.txt).
     */
    @Test fun releasedCodesMatchTheSnapshot() {
        val snapshot = javaClass.getResource("/diagnostics/error-codes.txt")!!.readText()
            .lines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
        val released = snapshot.associate { (id, level, zh) -> id to (level to zh) }
        for ((id, expected) in released) {
            val code = ErrorCode.of(id) ?: throw AssertionError("released code $id was removed")
            assertEquals("$id changed", expected, code.level.name to code.zh)
        }
        val unlisted = ErrorCode.entries.map { it.id } - released.keys
        assertEquals("new codes must be appended to the snapshot", emptyList<String>(), unlisted)
    }
}
