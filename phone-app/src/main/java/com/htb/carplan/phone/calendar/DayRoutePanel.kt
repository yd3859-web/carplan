package com.htb.carplan.phone.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.phone.ui.argb
import com.htb.carplan.shared.calendar.CategoryColors
import com.htb.carplan.shared.calendar.KoreanHolidays
import com.htb.carplan.shared.data.CalendarEvent
import com.htb.carplan.shared.model.BuiltInCategory
import com.htb.carplan.shared.model.CustomCategory
import com.htb.carplan.shared.model.Stop
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * 그날 동선: the day's events as a vertical route. Between two events that both have
 * a place, a drive leg is drawn (travel time comes from Kakao Mobility later).
 */
@Composable
fun DayRoutePanel(
    date: LocalDate,
    events: List<CalendarEvent>,
    categories: List<CustomCategory>,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    /** Stops of each trip on this day (3장), keyed by event id. */
    tripStops: Map<String, List<Stop>> = emptyMap(),
    onOpenTrip: (CalendarEvent) -> Unit = {},
) {
    val ordered = events.sortedWith(compareBy({ !it.event.allDay }, { it.event.startTime ?: "" }))
    val trips = ordered.filter { it.event.categoryId == BuiltInCategory.TRIP.id }
    val allDay = ordered.filter { it.event.allDay && it.event.place == null } - trips.toSet()
    val route = ordered - allDay.toSet() - trips.toSet()
    Column(modifier.padding(20.dp)) {
        Text(dayTitle(date), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
        val sub = listOfNotNull(if (date == LocalDate.now()) "오늘" else null, KoreanHolidays.nameOf(date)).joinToString(" · ")
        if (sub.isNotEmpty()) Text(sub, fontSize = 15.sp, color = Palette.TextSoft)

        LazyColumn(Modifier.weight(1f).padding(top = 16.dp)) {
            if (events.isEmpty()) item { Text("이날은 일정이 없어요", color = Palette.TextSoft, fontSize = 16.sp) }
            items(trips, key = { "trip-" + it.groupId + it.event.id }) { trip ->
                TripBlock(trip, tripStops[trip.event.id].orEmpty(), categories) { onOpenTrip(trip) }
            }
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
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = Corner,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy),
        ) { Text("일정 추가", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
    }
}

/** A trip on this day: its stops in route order, tap anywhere to edit (3장). */
@Composable
private fun TripBlock(trip: CalendarEvent, stops: List<Stop>, categories: List<CustomCategory>, onOpen: () -> Unit) {
    val color = argb(CategoryColors.of(trip.event.categoryId, categories))
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(16.dp).background(color))
            Spacer(Modifier.width(8.dp))
            Text(trip.event.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.weight(1f))
            Text("편집", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Teal)
        }
        if (stops.isEmpty()) {
            Text("이날 장소 칸이 아직 없어요 · 눌러서 짜기", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 6.dp))
        }
        stops.forEachIndexed { i, s ->
            Row(Modifier.padding(top = 6.dp, start = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", Modifier.width(22.dp), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Palette.Teal)
                s.time?.let { Text(it, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Navy, modifier = Modifier.width(52.dp)) }
                Text(s.place.name, fontSize = 17.sp, color = Palette.TextStrong, modifier = Modifier.weight(1f))
            }
            if (s.memo.isNotBlank()) Text(s.memo, fontSize = 14.sp, color = Palette.ChipText, modifier = Modifier.padding(start = 33.dp))
        }
        HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun AllDayRow(item: CalendarEvent, categories: List<CustomCategory>) {
    val color = argb(CategoryColors.of(item.event.categoryId, categories))
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(16.dp).background(color))
        Spacer(Modifier.width(8.dp))
        Text(item.event.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextStrong, modifier = Modifier.weight(1f))
        Text("하루 종일", fontSize = 14.sp, color = Palette.TextSoft)
    }
}

@Composable
private fun RouteStop(item: CalendarEvent, categories: List<CustomCategory>, isLast: Boolean) {
    val e = item.event
    val color = argb(CategoryColors.of(e.categoryId, categories))
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Text(e.startTime ?: "", Modifier.width(56.dp).padding(top = 2.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextStrong)
        Column(Modifier.width(20.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 4.dp).size(12.dp).clip(CircleShape).background(if (e.place != null) Palette.Teal else color))
            if (!isLast) Box(Modifier.width(2.dp).weight(1f).background(Palette.Line))
        }
        Column(Modifier.padding(start = 8.dp, bottom = 10.dp).weight(1f)) {
            Text(e.place?.name?.takeIf { it.isNotBlank() } ?: e.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
            val meta = listOfNotNull(
                e.title.takeIf { e.place != null && it != e.place?.name },
                CategoryColors.label(e.categoryId, categories),
                e.reminderMinutes?.let { "알림 ${reminderLabel(it)}" },
            ).joinToString("  ·  ")
            Text(meta, fontSize = 14.sp, color = Palette.TextSoft)
            if (e.memo.isNotBlank()) Text(e.memo, fontSize = 15.sp, color = Palette.ChipText, modifier = Modifier.padding(top = 2.dp))
            e.checklist.forEach { c -> Text((if (c.done) "✓ " else "– ") + c.text, fontSize = 15.sp, color = if (c.done) Palette.TextSoft else Palette.ChipText) }
        }
    }
}

/** The gap between two stops; a car leg when both ends are places. */
@Composable
private fun Leg(drive: Boolean) {
    Row(Modifier.fillMaxWidth().height(28.dp)) {
        Spacer(Modifier.width(56.dp))
        Box(Modifier.width(20.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(if (drive) Palette.Teal.copy(alpha = 0.5f) else Palette.Line))
        }
        if (drive) Text("차로 이동", Modifier.padding(start = 8.dp).align(Alignment.CenterVertically), fontSize = 14.sp, color = Palette.Teal)
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

