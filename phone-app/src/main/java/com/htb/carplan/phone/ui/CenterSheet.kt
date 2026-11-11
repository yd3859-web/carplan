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
import androidx.compose.ui.unit.sp
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
            val maxH = maxHeight
            val maxW = when { maxWidth >= 1200.dp -> 980.dp; maxWidth >= 720.dp -> 720.dp; else -> maxWidth }
            // Grows in slightly instead of popping (same as the car's windows).
            Enter(Entry.SHEET) { Box(
                Modifier
                    .widthIn(max = maxW)
                    .heightIn(max = maxH)
                    .shadow(16.dp, Corner, ambientColor = androidx.compose.ui.graphics.Color(0x33000000), spotColor = androidx.compose.ui.graphics.Color(0x33000000))
                    .clip(Corner)
                    .background(Palette.Sheet)
                    .border(1.dp, Palette.SheetEdge, Corner)
                    .padding(horizontal = 32.dp, vertical = 28.dp),
            ) { content() } }
        }
    }
}

/** A sheet's title: one size everywhere. */
@Composable
fun SheetTitle(text: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(text, fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Palette.TextStrong, modifier = modifier)
}

/** An input inside a sheet: grey box, 4dp corners, no Material underline (디자인 규칙: 색 바탕은 입력 필드만). */
@Composable
fun sheetFieldColors() = androidx.compose.material3.TextFieldDefaults.colors(
    focusedContainerColor = Palette.InputBg, unfocusedContainerColor = Palette.InputBg, disabledContainerColor = Palette.InputBg,
    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
    disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
    cursorColor = Palette.TextStrong, focusedTextColor = Palette.TextStrong, unfocusedTextColor = Palette.TextStrong,
    focusedPlaceholderColor = Palette.TextSoft, unfocusedPlaceholderColor = Palette.TextSoft,
)

/**
 * A sheet's bottom row: [취소] white with an edge, the main action black (주요 = 검정 채움, 보조 = 흰 바탕 + 테두리), both
 * the same height so the pair reads as one decision.
 */
@Composable
fun SheetActions(action: String, onAction: () -> Unit, onCancel: () -> Unit, enabled: Boolean = true, cancel: String = "취소", modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp, Alignment.End)) {
        androidx.compose.material3.OutlinedButton(onClick = onCancel, shape = Corner, modifier = Modifier.heightIn(min = 48.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Palette.SheetEdge),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = Palette.TextStrong),
        ) { androidx.compose.material3.Text(cancel, fontSize = 17.sp) }
        androidx.compose.material3.Button(onClick = onAction, enabled = enabled, shape = Corner, modifier = Modifier.heightIn(min = 48.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Palette.Navy, contentColor = androidx.compose.ui.graphics.Color.White,
                disabledContainerColor = Palette.Line, disabledContentColor = Palette.TextSoft),
        ) { androidx.compose.material3.Text(action, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) }
    }
}
