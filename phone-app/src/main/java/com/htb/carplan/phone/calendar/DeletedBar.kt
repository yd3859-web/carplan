package com.htb.carplan.phone.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.shared.data.CalendarRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * "지웠어요 · 되살리기" (QA 2026-11-04: an event or trip deleted had no way back). Whoever deletes puts it here; the
 * calendar shows the bar for 6 seconds. Deleting is soft, so bringing it back is one write.
 */
object JustDeleted {
    data class Item(val groupId: String, val eventId: String, val title: String)
    val item = MutableStateFlow<Item?>(null)
}

@Composable
fun DeletedBar(modifier: Modifier = Modifier) {
    val it by JustDeleted.item.collectAsState()
    val scope = rememberCoroutineScope()
    val d = it ?: return
    LaunchedEffect(d) { kotlinx.coroutines.delay(8_000); if (JustDeleted.item.value == d) JustDeleted.item.value = null }
    Row(modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 84.dp).background(Palette.Navy, Corner).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${d.title.ifBlank { "일정" }} 지웠어요", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = {
            JustDeleted.item.value = null
            scope.launch { runCatching { CalendarRepository().setEventDeleted(d.groupId, d.eventId, false) } }
        }) { Text("되살리기", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
    }
}
