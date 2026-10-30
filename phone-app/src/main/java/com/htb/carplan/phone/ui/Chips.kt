package com.htb.carplan.phone.ui

import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Design rule: selected chips are ink-filled, never the Material purple. Use on every FilterChip. */
@Composable
fun inkChipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors(
    containerColor = Palette.Background, labelColor = Palette.TextStrong,
    selectedContainerColor = Palette.Navy, selectedLabelColor = Color.White,
)
