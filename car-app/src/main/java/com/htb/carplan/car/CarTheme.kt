package com.htb.carplan.car

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Car colours in two sets (정원 2026-10-31: 다크 모드). Day = the Pleos look (white ground, near-black text, one
 * blue accent). Night = the same drawing on a black ground, so the screen stops glaring when the car dims.
 * The screen follows the car's night mode; the 시연 메뉴 can force it for a look.
 */
data class CarColors(
    val bg: Color,
    val ink: Color,
    val soft: Color,
    val line: Color,
    val accent: Color,
    val warn: Color,
    val railPassed: Color,
    /** Empty rail node edge. */
    val nodeEdge: Color,
    /** Dialog sheet + its edge. */
    val sheet: Color,
    val sheetEdge: Color,
    /** The question / notice band and what sits on it. */
    val band: Color,
    val onBand: Color,
    val bandSoft: Color,
    val bandEdge: Color,
    /** Text on an [ink]-filled button. */
    val onInk: Color,
    val disabled: Color,
    /** The other app's half in the 반반 preview. */
    val placeholder: Color,
    val dark: Boolean,
)

val LightCarColors = CarColors(
    bg = Color(0xFFFFFFFF), ink = Color(0xFF111111), soft = Color(0xFF666C73), line = Color(0xFFE4E6E9),
    accent = Color(0xFF0066B3), warn = Color(0xFFD9342B), railPassed = Color(0xFFB9BEC4), nodeEdge = Color(0xFFAEB4BA),
    sheet = Color(0xFFF0F2F5), sheetEdge = Color(0xFFC9CED4),
    band = Color(0xFF111111), onBand = Color(0xFFFFFFFF), bandSoft = Color(0xFFB8BDC2), bandEdge = Color(0xFF55595E),
    onInk = Color(0xFFFFFFFF), disabled = Color(0xFFD5D8DC), placeholder = Color(0xFFE9ECEF), dark = false,
)

val DarkCarColors = CarColors(
    bg = Color(0xFF000000), ink = Color(0xFFF2F3F4), soft = Color(0xFFA3A8AE), line = Color(0xFF2A2E33),
    // A lighter blue: the day blue (#0066B3) is too dim on black.
    accent = Color(0xFF4DA3FF), warn = Color(0xFFFF6B5E), railPassed = Color(0xFF5A6068), nodeEdge = Color(0xFF5A6068),
    sheet = Color(0xFF17191C), sheetEdge = Color(0xFF353A40),
    // On black the band is a dark grey with an edge, not black-on-black.
    band = Color(0xFF1E2227), onBand = Color(0xFFFFFFFF), bandSoft = Color(0xFFAEB4BA), bandEdge = Color(0xFF4A5056),
    onInk = Color(0xFF111111), disabled = Color(0xFF34383D), placeholder = Color(0xFF15181B), dark = true,
)

val LocalCarColors = staticCompositionLocalOf { LightCarColors }

/** The car app's theme: night colours when the car is in night mode or the 시연 메뉴 forces them. */
@Composable
fun CarTheme(forceDark: Boolean = false, content: @Composable () -> Unit) {
    val dark = forceDark || isSystemInDarkTheme()
    val c = if (dark) DarkCarColors else LightCarColors
    val scheme = if (dark) darkColorScheme(primary = c.ink, secondary = c.accent, background = c.bg, surface = c.bg, onSurface = c.ink, outline = c.line)
    else lightColorScheme(primary = c.ink, secondary = c.accent, background = c.bg, surface = c.bg, onSurface = c.ink, outline = c.line)
    CompositionLocalProvider(LocalCarColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
