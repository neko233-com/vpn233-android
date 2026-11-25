package com.neko233.vpn233.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.neko233.vpn233.data.ThemeMode

data class Vpn233Palette(
    val isDark: Boolean,
    val bg: Color,
    val card: Color,
    val cardElevated: Color,
    val border: Color,
    val accent: Color,
    val accentSecondary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val success: Color,
    val warning: Color,
    val danger: Color
)

private val DarkPalette = Vpn233Palette(
    isDark = true,
    bg = Color(0xFF070B12),
    card = Color(0xFF0F1724),
    cardElevated = Color(0xFF172235),
    border = Color(0xFF22314A),
    accent = Color(0xFF10B981),
    accentSecondary = Color(0xFF06B6D4),
    textPrimary = Color(0xFFF1F5F9),
    textSecondary = Color(0xFF94A3B8),
    success = Color(0xFF34D399),
    warning = Color(0xFFFBBF24),
    danger = Color(0xFFFB7185)
)

private val LightPalette = Vpn233Palette(
    isDark = false,
    bg = Color(0xFFF3F6FA),
    card = Color(0xFFFFFFFF),
    cardElevated = Color(0xFFEAF0F7),
    border = Color(0xFFD7E0EC),
    accent = Color(0xFF059669),
    accentSecondary = Color(0xFF0891B2),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF475569),
    success = Color(0xFF059669),
    warning = Color(0xFFD97706),
    danger = Color(0xFFE11D48)
)

val LocalVpnPalette = staticCompositionLocalOf { DarkPalette }

@Composable
fun Vpn233Theme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val palette = if (dark) DarkPalette else LightPalette
    val colorScheme = if (dark) {
        darkColorScheme(
            primary = palette.accent,
            secondary = palette.accentSecondary,
            background = palette.bg,
            surface = palette.card,
            surfaceVariant = palette.cardElevated,
            onPrimary = Color(0xFF042117),
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.border,
            error = palette.danger
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            secondary = palette.accentSecondary,
            background = palette.bg,
            surface = palette.card,
            surfaceVariant = palette.cardElevated,
            onPrimary = Color.White,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.border,
            error = palette.danger
        )
    }

    CompositionLocalProvider(LocalVpnPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
