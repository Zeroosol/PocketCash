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

/** PocketCash's own colors on top of Material: emerald for cash in, quiet neutrals for spending. */
@Immutable
data class CashColors(
    val cashIn: Color,
    val cashInContainer: Color,
    val onCashInContainer: Color,
    val spendContainer: Color,
    val onSpendContainer: Color,
    val over: Color,
    val card: Color,
    val subtle: Color,
)

private val LightCash = CashColors(
    cashIn = Color(0xFF0E9F6E),
    cashInContainer = Color(0xFFD1FAE5),
    onCashInContainer = Color(0xFF065F46),
    spendContainer = Color(0xFFE9EBEE),
    onSpendContainer = Color(0xFF111827),
    over = Color(0xFFDC2626),
    card = Color(0xFFFFFFFF),
    subtle = Color(0xFF6B7280),
)

private val DarkCash = CashColors(
    cashIn = Color(0xFF34D399),
    cashInContainer = Color(0xFF064E3B),
    onCashInContainer = Color(0xFFA7F3D0),
    spendContainer = Color(0xFF262B29),
    onSpendContainer = Color(0xFFF3F4F6),
    over = Color(0xFFF87171),
    card = Color(0xFF161B19),
    subtle = Color(0xFF9CA3AF),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0E9F6E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = Color(0xFF065F46),
    secondary = Color(0xFF4B5563),
    background = Color(0xFFF3F4F6),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFF3F4F6),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE9EBEE),
    onSurfaceVariant = Color(0xFF4B5563),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    error = Color(0xFFDC2626),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF34D399),
    onPrimary = Color(0xFF052E21),
    primaryContainer = Color(0xFF064E3B),
    onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = Color(0xFF9CA3AF),
    background = Color(0xFF0B0F0E),
    onBackground = Color(0xFFF3F4F6),
    surface = Color(0xFF0B0F0E),
    onSurface = Color(0xFFF3F4F6),
    surfaceVariant = Color(0xFF262B29),
    onSurfaceVariant = Color(0xFFB4BAB7),
    surfaceContainerLow = Color(0xFF161B19),
    surfaceContainer = Color(0xFF161B19),
    surfaceContainerHigh = Color(0xFF1D2321),
    error = Color(0xFFF87171),
)

val LocalCashColors = staticCompositionLocalOf { LightCash }

/** Tabular digits, so amounts never jitter while they change (docs/spec.md section 3). */
val Tabular = TextStyle(fontFeatureSettings = "tnum")

val HeroStyle = TextStyle(fontFeatureSettings = "tnum", fontWeight = FontWeight.Bold, fontSize = 60.sp, letterSpacing = (-1).sp)

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
