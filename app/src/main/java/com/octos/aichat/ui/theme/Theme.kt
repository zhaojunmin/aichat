package com.octos.aichat.ui.theme

import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.octos.aichat.R

/**
 * 日系极简 · 深蓝。冷调的靛蓝为主(primary),暖调的陶土 / 砂color作点缀(tertiary),
 * 配米纸 / 墨色的低饱和背景——冷暖相映、质感克制。提供四种明暗变体。
 */

// ---- 纸 Paper (light · warm washi + deep indigo) ----
private val PaperScheme = lightColorScheme(
    primary = Color(0xFF3C5A77),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E2EF),
    onPrimaryContainer = Color(0xFF16344E),
    secondary = Color(0xFF55606B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDBE1E7),
    onSecondaryContainer = Color(0xFF1A2228),
    tertiary = Color(0xFF9A6A47),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF2DAC7),
    onTertiaryContainer = Color(0xFF32190A),
    error = Color(0xFFA33A32),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF5DAD5),
    onErrorContainer = Color(0xFF3B0906),
    background = Color(0xFFF5F2EA),
    onBackground = Color(0xFF2A2A28),
    surface = Color(0xFFF8F5EE),
    onSurface = Color(0xFF2A2A28),
    surfaceVariant = Color(0xFFE6E1D6),
    onSurfaceVariant = Color(0xFF54504A),
    outline = Color(0xFF8C877C),
    outlineVariant = Color(0xFFD9D3C6),
    surfaceContainerLow = Color(0xFFF3EFE6),
    surfaceContainer = Color(0xFFEEEAE0),
    surfaceContainerHigh = Color(0xFFE8E3D8),
)

// ---- 雾 Mist (light · cool) ----
private val MistScheme = lightColorScheme(
    primary = Color(0xFF2E5267),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFE2EC),
    onPrimaryContainer = Color(0xFF0C2E3D),
    secondary = Color(0xFF51616B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6E0E6),
    onSecondaryContainer = Color(0xFF151E24),
    tertiary = Color(0xFF9A6A47),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF2DAC7),
    onTertiaryContainer = Color(0xFF32190A),
    error = Color(0xFFA33A32),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF5DAD5),
    onErrorContainer = Color(0xFF3B0906),
    background = Color(0xFFEDF1F4),
    onBackground = Color(0xFF1E2A30),
    surface = Color(0xFFF6F9FB),
    onSurface = Color(0xFF1E2A30),
    surfaceVariant = Color(0xFFDDE3E8),
    onSurfaceVariant = Color(0xFF49535A),
    outline = Color(0xFF8A949B),
    outlineVariant = Color(0xFFCAD2D8),
    surfaceContainerLow = Color(0xFFF0F4F6),
    surfaceContainer = Color(0xFFEAEFF2),
    surfaceContainerHigh = Color(0xFFE3EAED),
)

// ---- 蓝夜 Ink (dark · deep navy-ink + soft sky, warm sand accent) ----
private val InkScheme = darkColorScheme(
    primary = Color(0xFF9FC1DC),
    onPrimary = Color(0xFF08283E),
    primaryContainer = Color(0xFF274155),
    onPrimaryContainer = Color(0xFFCFE3F2),
    secondary = Color(0xFFADBAC6),
    onSecondary = Color(0xFF1E2830),
    secondaryContainer = Color(0xFF353F49),
    onSecondaryContainer = Color(0xFFD5DEE6),
    tertiary = Color(0xFFE0B48C),
    onTertiary = Color(0xFF40260F),
    tertiaryContainer = Color(0xFF5A3F27),
    onTertiaryContainer = Color(0xFFF5D8BE),
    error = Color(0xFFE89A90),
    onError = Color(0xFF57130C),
    errorContainer = Color(0xFF73302A),
    onErrorContainer = Color(0xFFF9DAD5),
    background = Color(0xFF111822),
    onBackground = Color(0xFFE2E6EA),
    surface = Color(0xFF16202C),
    onSurface = Color(0xFFE2E6EA),
    surfaceVariant = Color(0xFF3A4652),
    onSurfaceVariant = Color(0xFFC0C8D0),
    outline = Color(0xFF4C5864),
    outlineVariant = Color(0xFF2E3A46),
    surfaceContainerLow = Color(0xFF141D27),
    surfaceContainer = Color(0xFF18222E),
    surfaceContainerHigh = Color(0xFF202C38),
)

// ---- 墨 Midnight (dark · near-black) ----
private val MidnightScheme = darkColorScheme(
    primary = Color(0xFFB0C8DF),
    onPrimary = Color(0xFF0B1E2C),
    primaryContainer = Color(0xFF22303E),
    onPrimaryContainer = Color(0xFFD5E5F2),
    secondary = Color(0xFFADBAC6),
    onSecondary = Color(0xFF1A242C),
    secondaryContainer = Color(0xFF2C343D),
    onSecondaryContainer = Color(0xFFD5DEE6),
    tertiary = Color(0xFFD9AF88),
    onTertiary = Color(0xFF3A220D),
    tertiaryContainer = Color(0xFF503622),
    onTertiaryContainer = Color(0xFFF2D4BA),
    error = Color(0xFFE89A90),
    onError = Color(0xFF57130C),
    errorContainer = Color(0xFF73302A),
    onErrorContainer = Color(0xFFF9DAD5),
    background = Color(0xFF0B0E12),
    onBackground = Color(0xFFDBE0E5),
    surface = Color(0xFF10141A),
    onSurface = Color(0xFFDBE0E5),
    surfaceVariant = Color(0xFF333B44),
    onSurfaceVariant = Color(0xFFBBC3CB),
    outline = Color(0xFF454D57),
    outlineVariant = Color(0xFF262D35),
    surfaceContainerLow = Color(0xFF0E1217),
    surfaceContainer = Color(0xFF13181F),
    surfaceContainerHigh = Color(0xFF1A2129),
)

// ---- 月白 Qingbai (light · 新中式, Morandi celadon on moon-white) ----
private val QingbaiScheme = lightColorScheme(
    primary = Color(0xFF6F9A94),          // 天青 celadon
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFE0DC),
    onPrimaryContainer = Color(0xFF0E2C28),
    secondary = Color(0xFF627079),        // 黛 slate
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E0E2),
    onSecondaryContainer = Color(0xFF18242A),
    tertiary = Color(0xFFA97E7A),         // 藕/胭脂 muted rose (暖)
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEEDAD6),
    onTertiaryContainer = Color(0xFF331D1A),
    error = Color(0xFF9C4A42),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF3DBD7),
    onErrorContainer = Color(0xFF36100C),
    background = Color(0xFFEAEFEF),        // 月白
    onBackground = Color(0xFF242A29),
    surface = Color(0xFFF2F6F5),
    onSurface = Color(0xFF242A29),
    surfaceVariant = Color(0xFFDDE4E3),
    onSurfaceVariant = Color(0xFF434A49),
    outline = Color(0xFF94A09D),
    outlineVariant = Color(0xFFC7D0CE),
    surfaceContainerLow = Color(0xFFEEF3F2),
    surfaceContainer = Color(0xFFE8EEED),
    surfaceContainerHigh = Color(0xFFE1E8E7),
)

// ---- 黛青 Daiqing (dark · 新中式) ----
private val DaiqingScheme = darkColorScheme(
    primary = Color(0xFF9CC0B9),          // soft celadon
    onPrimary = Color(0xFF0A302A),
    primaryContainer = Color(0xFF2C4540),
    onPrimaryContainer = Color(0xFFBFE0D9),
    secondary = Color(0xFFAEBAB9),
    onSecondary = Color(0xFF1E2828),
    secondaryContainer = Color(0xFF343E3D),
    onSecondaryContainer = Color(0xFFD4DEDC),
    tertiary = Color(0xFFD6AEA9),         // muted rose (暖)
    onTertiary = Color(0xFF3E2420),
    tertiaryContainer = Color(0xFF583C38),
    onTertiaryContainer = Color(0xFFF3D9D4),
    error = Color(0xFFE2A099),
    onError = Color(0xFF511A14),
    errorContainer = Color(0xFF6E322B),
    onErrorContainer = Color(0xFFF8DAD5),
    background = Color(0xFF14191A),        // 黛
    onBackground = Color(0xFFDDE3E1),
    surface = Color(0xFF1A2120),
    onSurface = Color(0xFFDDE3E1),
    surfaceVariant = Color(0xFF3A4442),
    onSurfaceVariant = Color(0xFFBEC8C6),
    outline = Color(0xFF4A5553),
    outlineVariant = Color(0xFF2C3634),
    surfaceContainerLow = Color(0xFF171E1E),
    surfaceContainer = Color(0xFF1B2322),
    surfaceContainerHigh = Color(0xFF232B2A),
)

/** The selectable appearance variants. "system" is handled by [AiChatTheme], not here. */
enum class ThemeVariant(val id: String, @StringRes val labelRes: Int, val dark: Boolean) {
    PAPER("paper", R.string.theme_paper, false),
    MIST("mist", R.string.theme_mist, false),
    QINGBAI("qingbai", R.string.theme_qingbai, false),
    INK("ink", R.string.theme_ink, true),
    MIDNIGHT("midnight", R.string.theme_midnight, true),
    DAIQING("daiqing", R.string.theme_daiqing, true);

    companion object {
        fun of(id: String?): ThemeVariant? = entries.firstOrNull { it.id == id }
    }
}

/** Restrained, slightly crisp corners — minimal rather than playful. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Apply a specific [variant]. Pass the id "system" (or null) to follow the device's
 * light/dark setting, mapping to [ThemeVariant.PAPER] / [ThemeVariant.INK].
 */
@Composable
fun AiChatTheme(
    variantId: String?,
    content: @Composable () -> Unit,
) {
    val resolved = ThemeVariant.of(variantId)
        ?: if (isSystemInDarkTheme()) ThemeVariant.INK else ThemeVariant.PAPER
    val scheme = when (resolved) {
        ThemeVariant.PAPER -> PaperScheme
        ThemeVariant.MIST -> MistScheme
        ThemeVariant.QINGBAI -> QingbaiScheme
        ThemeVariant.INK -> InkScheme
        ThemeVariant.MIDNIGHT -> MidnightScheme
        ThemeVariant.DAIQING -> DaiqingScheme
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
