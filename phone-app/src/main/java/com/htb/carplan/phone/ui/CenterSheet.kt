package com.htb.carplan.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Every picker/editor opens as a centered dialog, not a bottom sheet: in the car the
 * bottom of the screen is far from the driver, and the middle is where the eyes already are.
 * Car-wide screens get a 980dp panel, tablets 720dp, phones almost the full width.
 */
@Composable
fun CenterSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .widthIn(max = when { maxWidth >= 1200.dp -> 980.dp; maxWidth >= 720.dp -> 720.dp; else -> maxWidth })
                    .heightIn(max = maxHeight)
                    .shadow(24.dp, Corner)
                    .clip(Corner)
                    .background(Palette.Sheet)
                    .border(1.dp, Palette.SheetEdge, Corner)
                    .padding(horizontal = 32.dp, vertical = 28.dp),
            ) { content() }
        }
    }
}
