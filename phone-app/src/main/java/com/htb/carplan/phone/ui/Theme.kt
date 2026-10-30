package com.htb.carplan.phone.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * carplan look, matched to the Hyundai (Pleos) system UI: white ground, near-black text,
 * hairline dividers instead of boxed sections, one blue accent for "the car goes there",
 * black primary buttons, 4dp corners. No emoji.
 */
object Palette {
    val Background = Color(0xFFFFFFFF)
    /** Input fields and pressed states only, never whole sections. */
    val Panel = Color(0xFFF4F5F7)
    /** Dialogs sit on a light grey sheet with a visible edge so they read as a layer over the white page. */
    val Sheet = Color(0xFFF0F2F5)
    val SheetEdge = Color(0xFFC9CED4)
    /** Primary (buttons, today marker). Kept under its old name so call sites stay. */
    val Navy = Color(0xFF111111)
    val NavySoft = Color(0xFF2B2F33)
    /** Accent: anything that moves the car (places, routes, next destination). */
    val Teal = Color(0xFF0066B3)
    val TextStrong = Color(0xFF111111)
    val TextSoft = Color(0xFF8A9096)
    val Line = Color(0xFFE4E6E9)
    val Sunday = Color(0xFFD9342B)
    val Saturday = Color(0xFF2F6FD1)
    val InputBg = Color(0xFFF4F5F7)
    val ChipText = Color(0xFF4A5056)
    val Today = Navy
}

/** One corner radius everywhere (Hyundai UI is almost square). */
val Corner = RoundedCornerShape(4.dp)
val CornerDp = 4.dp

@Composable
fun CarplanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.Navy,
            onPrimary = Color.White,
            secondary = Palette.Teal,
            background = Palette.Background,
            surface = Palette.Background,
            surfaceContainer = Palette.Background,
            surfaceContainerHigh = Palette.Background,
            surfaceContainerLow = Palette.Background,
            onSurface = Palette.TextStrong,
            onSurfaceVariant = Palette.ChipText,
            outline = Palette.Line,
            outlineVariant = Palette.Line,
        ),
        shapes = Shapes(extraSmall = Corner, small = Corner, medium = Corner, large = Corner, extraLarge = Corner),
        content = content,
    )
}

fun argb(value: Long) = Color(value.toInt())
