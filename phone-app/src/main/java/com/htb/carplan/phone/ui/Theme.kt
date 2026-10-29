package com.htb.carplan.phone.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Light, airy look from docs/캘린더예시.
object Palette {
    val Background = Color(0xFFFFFFFF)
    val Panel = Color(0xFFF7F8FA)
    val TextStrong = Color(0xFF111318)
    val TextSoft = Color(0xFF8A8F99)
    val ChipText = Color(0xFF5B6380)
    val Sunday = Color(0xFFE5533D)
    val Saturday = Color(0xFF3B7BE0)
    val Today = Color(0xFF3B82F6)
    val InputBg = Color(0xFFEEF0F4)
}

@Composable
fun CarplanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.Today,
            background = Palette.Background,
            surface = Palette.Background,
            onSurface = Palette.TextStrong,
        ),
        content = content,
    )
}

fun argb(value: Long) = Color(value.toInt())
