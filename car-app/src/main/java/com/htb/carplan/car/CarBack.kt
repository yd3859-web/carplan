package com.htb.carplan.car

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The car has no system back key (Pleos GNB: 공조·앱 목록·최근 앱 only; apps close with a 3-finger swipe), so every
 * full-screen view starts with this one button, always top-left, always the same size — like the Pleos apps.
 * Dialogs keep their [닫기] at the bottom.
 */
@Composable
fun CarBackButton(onBack: () -> Unit, label: String = "뒤로") {
    val ink = LocalCarColors.current.ink
    TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 76.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = ink, modifier = Modifier.size(36.dp))
            Text(label, color = ink, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** A window's [닫기]: full-size outlined button, always first (bottom-left, driver side). */
@Composable
fun CarCloseButton(onClick: () -> Unit, label: String = "닫기") {
    val c = LocalCarColors.current
    androidx.compose.material3.OutlinedButton(
        onClick = onClick, modifier = Modifier.heightIn(min = 76.dp).widthIn(min = 160.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, c.sheetEdge),
    ) { Text(label, color = c.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
}
