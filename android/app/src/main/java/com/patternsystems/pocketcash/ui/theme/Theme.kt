package com.patternsystems.pocketcash.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * PocketCash's minimal palette (docs/spec.md section 7): one emerald accent, used only for cash in, progress bars and
 * the primary button. Everything else is monochrome on a plain background, separated by hairlines, never shadows.
 */
@Immutable
data class CashColors(
    val cashIn: Color,
    /** Muted emerald behind the cash-in buttons. */
    val cashInContainer: Color,
    val onCashInContainer: Color,
    /** The quiet grey of spend buttons, keypad keys and the nav bar. */
    val spendContainer: Color,
    val onSpendContainer: Color,
    val over: Color,
    /** A barely raised surface (the widget drawing in onboarding). */
    val card: Color,
    val subtle: Color,
    /** Hairline dividers and outlines. */
    val hairline: Color,
    /** The track behind progress bars. */
    val track: Color,
)

private val LightCash = CashColors(
    cashIn = Color(0xFF059669),
    cashInContainer = Color(0xFFE3F3EC),
    onCashInContainer = Color(0xFF065F46),
    spendContainer = Color(0xFFF0F0EF),
    onSpendContainer = Color(0xFF151617),
    over = Color(0xFFDC2626),
    card = Color(0xFFF4F4F3),
    subtle = Color(0xFF6B6F76),
    hairline = Color(0xFFE4E4E2),
    track = Color(0xFFEAEAE8),
)

private val DarkCash = CashColors(
    cashIn = Color(0xFF34D399),
    cashInContainer = Color(0xFF10291F),
    onCashInContainer = Color(0xFFA7F3D0),
    spendContainer = Color(0xFF1B1C1C),
    onSpendContainer = Color(0xFFF1F1F1),
    over = Color(0xFFF87171),
    card = Color(0xFF161717),
    subtle = Color(0xFF9A9EA4),
    hairline = Color(0xFF26282A),
    track = Color(0xFF232525),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF059669),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3F3EC),
    onPrimaryContainer = Color(0xFF065F46),
    secondary = Color(0xFF3F4246),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E6E5),
    onSecondaryContainer = Color(0xFF151617),
    background = Color(0xFFFAFAF9),
    onBackground = Color(0xFF151617),
    surface = Color(0xFFFAFAF9),
    onSurface = Color(0xFF151617),
    surfaceVariant = Color(0xFFEDEDEC),
    onSurfaceVariant = Color(0xFF55595F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F6F5),
    surfaceContainer = Color(0xFFF1F1F0),
    surfaceContainerHigh = Color(0xFFEBEBEA),
    surfaceContainerHighest = Color(0xFFE5E5E4),
    outline = Color(0xFFC9CACB),
    outlineVariant = Color(0xFFE4E4E2),
    error = Color(0xFFDC2626),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF34D399),
    onPrimary = Color(0xFF03241A),
    primaryContainer = Color(0xFF10291F),
    onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = Color(0xFFC4C7CB),
    onSecondary = Color(0xFF151617),
    secondaryContainer = Color(0xFF2A2C2D),
    onSecondaryContainer = Color(0xFFF1F1F1),
    background = Color(0xFF0C0D0D),
    onBackground = Color(0xFFF1F1F1),
    surface = Color(0xFF0C0D0D),
    onSurface = Color(0xFFF1F1F1),
    surfaceVariant = Color(0xFF232525),
    onSurfaceVariant = Color(0xFFB4B8BC),
    surfaceContainerLowest = Color(0xFF080909),
    surfaceContainerLow = Color(0xFF131414),
    surfaceContainer = Color(0xFF1A1B1B),
    surfaceContainerHigh = Color(0xFF212222),
    surfaceContainerHighest = Color(0xFF292A2A),
    outline = Color(0xFF45484B),
    outlineVariant = Color(0xFF26282A),
    error = Color(0xFFF87171),
)

val LocalCashColors = staticCompositionLocalOf { LightCash }

/** Tabular digits, so amounts never jitter while they change (docs/spec.md section 3). */
val Tabular = TextStyle(fontFeatureSettings = "tnum")

val HeroStyle = TextStyle(fontFeatureSettings = "tnum", fontWeight = FontWeight.SemiBold, fontSize = 64.sp, letterSpacing = (-1.5).sp)

@Composable
fun PocketCashTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme) {
        CompositionLocalProvider(LocalCashColors provides if (dark) DarkCash else LightCash, content = content)
    }
}

object Cash {
    val colors: CashColors
        @Composable get() = LocalCashColors.current
}
