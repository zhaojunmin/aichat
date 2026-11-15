package com.octos.aichat.bubble

import androidx.annotation.ColorInt
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb

/**
 * The overlay's colours as plain ARGB ints for classic Views, derived from the app's
 * Compose [ColorScheme] so the bubble and panel match the theme chosen in settings.
 */
internal data class BubblePalette(
    @ColorInt val ball: Int,
    @ColorInt val onBall: Int,
    @ColorInt val panel: Int,
    @ColorInt val title: Int,
    @ColorInt val muted: Int,
    @ColorInt val card: Int,
    @ColorInt val onCard: Int,
    @ColorInt val accent: Int,
) {
    companion object {
        fun from(scheme: ColorScheme) = BubblePalette(
            ball = scheme.primary.toArgb(),
            onBall = scheme.onPrimary.toArgb(),
            panel = scheme.surface.toArgb(),
            title = scheme.primary.toArgb(),
            muted = scheme.onSurfaceVariant.toArgb(),
            card = scheme.surfaceContainerHigh.toArgb(),
            onCard = scheme.onSurface.toArgb(),
            accent = scheme.primary.toArgb(),
        )
    }
}
