package com.octos.aichat.bubble

import androidx.compose.ui.graphics.toArgb
import com.octos.aichat.ui.theme.ThemeVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BubblePaletteTest {

    @Test
    fun paletteIsTakenFromThemeColorScheme() {
        val scheme = ThemeVariant.DAIQING.colorScheme
        val p = BubblePalette.from(scheme)
        assertEquals(scheme.primary.toArgb(), p.ball)
        assertEquals(scheme.onPrimary.toArgb(), p.onBall)
        assertEquals(scheme.surface.toArgb(), p.panel)
        assertEquals(scheme.onSurface.toArgb(), p.onCard)
    }

    @Test
    fun lightAndDarkThemesGiveDifferentOverlays() {
        val light = BubblePalette.from(ThemeVariant.PAPER.colorScheme)
        val dark = BubblePalette.from(ThemeVariant.INK.colorScheme)
        assertNotEquals(light.panel, dark.panel)
        assertNotEquals(light.ball, dark.ball)
    }
}
