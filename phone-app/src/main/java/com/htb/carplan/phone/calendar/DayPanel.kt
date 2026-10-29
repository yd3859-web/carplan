package com.htb.carplan.phone.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.phone.ui.argb
import com.htb.carplan.shared.calendar.CategoryColors
import com.htb.carplan.shared.calendar.KoreanHolidays
import com.htb.carplan.shared.data.CalendarEvent
import com.htb.carplan.shared.model.CustomCategory
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** "10월 29일 (목) · 오늘" + that day's events + "+ 할 일을 추가하세요" (예시 오른쪽 패널). */
@Composable
fun DayPanel(
    date: LocalDate,
    events: List<CalendarEvent>,
    categories: List<CustomCategory>,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(20.dp)) {
        Text(dayTitle(date), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
        val sub = listOfNotNull(if (date == LocalDate.now()) "오늘" else null, KoreanHolidays.nameOf(date)).joinToString(" · ")
        if (sub.isNotEmpty()) Text(sub, fontSize = 13.sp, color = Palette.TextSoft)
        LazyColumn(Modifier.weight(1f).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (events.isEmpty()) item { Text("일정이 없어요", color = Palette.TextSoft, fontSize = 14.sp) }
            items(events, key = { it.groupId + it.event.id }) { EventCard(it, categories) }
        }
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.InputBg).clickable(onClick = onAdd)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Text("+  할 일을 추가하세요", fontSize = 16.sp, color = Palette.TextStrong.copy(alpha = 0.75f))
        }
    }
}

@Composable
private fun EventCard(item: CalendarEvent, categories: List<CustomCategory>) {
    val e = item.event
    val color = argb(CategoryColors.of(e.categoryId, categories))
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.12f)).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(color))
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(e.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextStrong)
            val meta = listOfNotNull(
                CategoryColors.label(e.categoryId, categories),
                when {
                    e.startDate != e.endDate && e.endDate.isNotEmpty() -> "${shortDate(e.startDate)} – ${shortDate(e.endDate)}"
                    !e.allDay && e.startTime != null -> e.startTime
                    else -> "하루 종일"
                },
                e.place?.name?.takeIf { it.isNotBlank() }?.let { "📍 $it" },
                e.reminderMinutes?.let { "🔔 ${reminderLabel(it)}" },
            ).joinToString("  ·  ")
            Text(meta, fontSize = 12.sp, color = Palette.TextSoft)
            if (e.memo.isNotBlank()) Text(e.memo, fontSize = 13.sp, color = Palette.ChipText, modifier = Modifier.padding(top = 4.dp))
            e.checklist.forEach { c ->
                Text((if (c.done) "☑ " else "☐ ") + c.text, fontSize = 13.sp, color = Palette.ChipText)
            }
        }
    }
}

fun dayTitle(date: LocalDate): String =
    "${date.monthValue}월 ${date.dayOfMonth}일 (${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})"

private fun shortDate(iso: String): String = LocalDate.parse(iso).let { "${it.monthValue}/${it.dayOfMonth}" }

fun reminderLabel(minutes: Int): String = when {
    minutes == 0 -> "정시"
    minutes < 60 -> "${minutes}분 전"
    minutes < 1440 -> "${minutes / 60}시간 전"
    else -> "${minutes / 1440}일 전"
}
