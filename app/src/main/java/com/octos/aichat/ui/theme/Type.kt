package com.octos.aichat.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Refined type for a 新中式 / minimalist feel: elegant serif headings (on Android,
 * serif maps to Noto Serif CJK — a 宋体-style face that reads as 新中式), a sans body
 * with slightly looser tracking and taller line heights for calm, airy text.
 */
private val base = Typography()

val AppTypography = base.copy(
    // Serif, lighter weight, open tracking — poised headings.
    headlineSmall = base.headlineSmall.copy(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.5.sp,
    ),
    titleLarge = base.titleLarge.copy(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
    ),
    titleMedium = base.titleMedium.copy(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.3.sp,
    ),
    // Sans body, airier.
    bodyLarge = base.bodyLarge.copy(letterSpacing = 0.3.sp, lineHeight = 24.sp),
    bodyMedium = base.bodyMedium.copy(letterSpacing = 0.25.sp, lineHeight = 21.sp),
    bodySmall = base.bodySmall.copy(letterSpacing = 0.25.sp, lineHeight = 18.sp),
    labelLarge = base.labelLarge.copy(letterSpacing = 0.5.sp),
)
