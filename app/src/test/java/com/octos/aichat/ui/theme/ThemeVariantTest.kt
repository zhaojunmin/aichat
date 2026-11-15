package com.octos.aichat.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ThemeVariantTest {

    @Test
    fun `system follows device dark mode`() {
        assertEquals(ThemeVariant.PAPER, ThemeVariant.resolve("system", systemDark = false))
        assertEquals(ThemeVariant.INK, ThemeVariant.resolve("system", systemDark = true))
    }

    @Test
    fun `unknown or missing id falls back like system`() {
        assertEquals(ThemeVariant.PAPER, ThemeVariant.resolve(null, systemDark = false))
        assertEquals(ThemeVariant.INK, ThemeVariant.resolve("gone", systemDark = true))
    }

    @Test
    fun `explicit variant ignores device dark mode`() {
        assertEquals(ThemeVariant.QINGBAI, ThemeVariant.resolve("qingbai", systemDark = true))
        assertEquals(ThemeVariant.MIDNIGHT, ThemeVariant.resolve("midnight", systemDark = false))
    }

    @Test
    fun `every variant has its own palette`() {
        val primaries = ThemeVariant.entries.map { it.colorScheme.primary }
        assertEquals(primaries.size, primaries.toSet().size)
        ThemeVariant.entries.forEach { assertNotEquals(it.colorScheme.primary, it.colorScheme.onPrimary) }
    }
}
