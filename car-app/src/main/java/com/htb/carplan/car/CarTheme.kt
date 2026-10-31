package com.htb.carplan.car

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Car colours in two sets (정원 2026-10-31: 다크 모드, 현대 기준). Day = the Pleos look (white ground, near-black text,
 * one blue accent). Night = the Pleos dark mode ground, so the screen stops glaring when the car dims.
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

// Values from Hyundai's Pleos Connect design assets (Figma variables, Light/Dark modes; 2026-10-31):
// text/dark/primary #131417, text tertiary = 64%, divider/secondary = 10%, background/secondary, background/popup,
// button/filled/enabled, informative/positive (the system blue), Secondary/Basic/Nagative. Alpha tokens are
// pre-mixed over the screen background so they stay readable on both.
val LightCarColors = CarColors(
    bg = Color(0xFFFFFFFF), ink = Color(0xFF131417), soft = Color(0xFF68696A), line = Color(0xFFE7E7E8),
    accent = Color(0xFF0064FF), warn = Color(0xFFFE3D16), railPassed = Color(0xFFB8B9BB), nodeEdge = Color(0xFFA1A1A2),
    sheet = Color(0xFFEDEEF2), sheetEdge = Color(0xFFD0D0D1),
    band = Color(0xFF131417), onBand = Color(0xFFFFFFFF), bandSoft = Color(0xFFB8B9BB), bandEdge = Color(0xFF44464E),
    onInk = Color(0xFFFFFFFF), disabled = Color(0xFFE0E1E6), placeholder = Color(0xFFEDEEF2), dark = false,
)

val DarkCarColors = CarColors(
    bg = Color(0xFF131417), ink = Color(0xFFFFFFFF), soft = Color(0xFFAAAAAB), line = Color(0xFF2B2C2F),
    accent = Color(0xFF0082FF), warn = Color(0xFFFF4C28), railPassed = Color(0xFF5A5B5D), nodeEdge = Color(0xFF6B6C6E),
    sheet = Color(0xFF313236), sheetEdge = Color(0xFF44464E),
    // On the dark screen the band is the popup grey with an edge, not black-on-black.
    band = Color(0xFF313236), onBand = Color(0xFFFFFFFF), bandSoft = Color(0xFFAAAAAB), bandEdge = Color(0xFF44464E),
    onInk = Color(0xFF131417), disabled = Color(0xFF313236), placeholder = Color(0xFF1D1E21), dark = true,
)

val LocalCarColors = staticCompositionLocalOf { LightCarColors }

/** Our window is half the screen (next to the navigation, Pleos 1:1): every screen has a narrow layout (정원 2026-10-31). */
val LocalCompact = androidx.compose.runtime.compositionLocalOf { false }

/** Narrower than this = the half-screen layout. */
val CompactBelow = 1100.dp

/** The car's own UI font when present (Pleos: Asta Sans as "pretendard"); Typeface.create falls back to the default. */
@OptIn(ExperimentalTextApi::class)
private val PleosFont: FontFamily = DeviceFontFamilyName("pretendard").let { f ->
    FontFamily(
        listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold, FontWeight.Black)
            .map { w -> Font(familyName = f, weight = w) },
    )
}

/** The car app's theme: night colours when the car is in night mode or the 시연 메뉴 forces them. */
@Composable
fun CarTheme(forceDark: Boolean = false, content: @Composable () -> Unit) {
    val dark = forceDark || isSystemInDarkTheme()
    val c = if (dark) DarkCarColors else LightCarColors
    val scheme = if (dark) darkColorScheme(primary = c.ink, secondary = c.accent, background = c.bg, surface = c.bg, onSurface = c.ink, outline = c.line)
    else lightColorScheme(primary = c.ink, secondary = c.accent, background = c.bg, surface = c.bg, onSurface = c.ink, outline = c.line)
    CompositionLocalProvider(LocalCarColors provides c) {
        MaterialTheme(colorScheme = scheme) {
            // Pleos system font (Asta Sans, registered on the car as the "pretendard" family); default font elsewhere.
            ProvideTextStyle(LocalTextStyle.current.copy(fontFamily = PleosFont), content)
        }
    }
}
