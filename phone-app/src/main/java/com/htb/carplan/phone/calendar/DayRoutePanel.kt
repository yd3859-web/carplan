package com.htb.carplan.phone.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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

/**
 * 그날 동선: the day's events as a vertical route. Between two events that both have
 * a place, a 🚗 leg is drawn (travel time comes from Kakao Mobility later).
 */
@Composable
fun DayRoutePanel(
    date: LocalDate,
    events: List<CalendarEvent>,
    categories: List<CustomCategory>,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ordered = events.sortedWith(compareBy({ !it.event.allDay }, { it.event.startTime ?: "" }))
    val allDay = ordered.filter { it.event.allDay && it.event.place == null }
    val route = ordered - allDay.toSet()
    Column(modifier.padding(20.dp)) {
        Text(dayTitle(date), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
        val sub = listOfNotNull(if (date == LocalDate.now()) "오늘" else null, KoreanHolidays.nameOf(date)).joinToString(" · ")
        if (sub.isNotEmpty()) Text(sub, fontSize = 13.sp, color = Palette.TextSoft)

        LazyColumn(Modifier.weight(1f).padding(top = 16.dp)) {
            if (events.isEmpty()) item { Text("이날은 일정이 없어요", color = Palette.TextSoft, fontSize = 14.sp) }
            if (allDay.isNotEmpty()) item {
                Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    allDay.forEach { AllDayRow(it, categories) }
                }
            }
            itemsIndexed(route, key = { _, it -> it.groupId + it.event.id }) { i, item ->
                val prev = route.getOrNull(i - 1)
                if (prev != null) Leg(drive = prev.event.place != null && item.event.place != null)
                RouteStop(item, categories, isLast = i == route.lastIndex)
            }
        }
        Button(
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy),
        ) { Text("+ 일정 추가", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun AllDayRow(item: CalendarEvent, categories: List<CustomCategory>) {
    val color = argb(CategoryColors.of(item.event.categoryId, categories))
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(item.event.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextStrong, modifier = Modifier.weight(1f))
        Text("하루 종일", fontSize = 12.sp, color = Palette.TextSoft)
    }
}

@Composable
private fun RouteStop(item: CalendarEvent, categories: List<CustomCategory>, isLast: Boolean) {
    val e = item.event
    val color = argb(CategoryColors.of(e.categoryId, categories))
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Text(e.startTime ?: "", Modifier.width(48.dp).padding(top = 2.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextStrong)
        Column(Modifier.width(20.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 4.dp).size(12.dp).clip(CircleShape).background(if (e.place != null) Palette.Teal else color))
            if (!isLast) Box(Modifier.width(2.dp).weight(1f).background(Palette.Line))
        }
        Column(Modifier.padding(start = 8.dp, bottom = 10.dp).weight(1f)) {
            Text(e.place?.name?.takeIf { it.isNotBlank() } ?: e.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
            val meta = listOfNotNull(
                e.title.takeIf { e.place != null && it != e.place?.name },
                CategoryColors.label(e.categoryId, categories),
                e.reminderMinutes?.let { "🔔 ${reminderLabel(it)}" },
            ).joinToString("  ·  ")
            Text(meta, fontSize = 12.sp, color = Palette.TextSoft)
            if (e.memo.isNotBlank()) Text(e.memo, fontSize = 13.sp, color = Palette.ChipText, modifier = Modifier.padding(top = 2.dp))
            e.checklist.forEach { c -> Text((if (c.done) "☑ " else "☐ ") + c.text, fontSize = 13.sp, color = Palette.ChipText) }
        }
    }
}

/** The gap between two stops; a car leg when both ends are places. */
@Composable
private fun Leg(drive: Boolean) {
    Row(Modifier.fillMaxWidth().height(28.dp)) {
        Spacer(Modifier.width(48.dp))
        Box(Modifier.width(20.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(if (drive) Palette.Teal.copy(alpha = 0.5f) else Palette.Line))
        }
        if (drive) Text("🚗 차로 이동", Modifier.padding(start = 8.dp).align(Alignment.CenterVertically), fontSize = 12.sp, color = Palette.Teal)
    }
}

fun dayTitle(date: LocalDate): String =
    "${date.monthValue}월 ${date.dayOfMonth}일 (${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})"

fun reminderLabel(minutes: Int): String = when {
    minutes == 0 -> "정시"
    minutes < 60 -> "${minutes}분 전"
    minutes < 1440 -> "${minutes / 60}시간 전"
    else -> "${minutes / 1440}일 전"
}

