package com.htb.carplan.phone.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// carplan look: white calendar, deep navy "today" card, teal for anything that moves the car.
object Palette {
    val Background = Color(0xFFFFFFFF)
    val Panel = Color(0xFFF5F7FA)
    val Navy = Color(0xFF16213A)
    val NavySoft = Color(0xFF2A3A5E)
    val Teal = Color(0xFF1FB5A5)
    val TextStrong = Color(0xFF141A26)
    val TextSoft = Color(0xFF8A93A3)
    val Line = Color(0xFFE3E7EE)
    val Sunday = Color(0xFFE5533D)
    val Saturday = Color(0xFF3B7BE0)
    val InputBg = Color(0xFFEEF1F5)
    val ChipText = Color(0xFF3D4760)
    val Today = Navy
}

@Composable
fun CarplanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.Navy,
            background = Palette.Background,
            surface = Palette.Background,
            onSurface = Palette.TextStrong,
        ),
        content = content,
    )
}

fun argb(value: Long) = Color(value.toInt())
