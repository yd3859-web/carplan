package com.htb.carplan.phone.calendar

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.NodeState
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.phone.ui.RailNode
import com.htb.carplan.phone.ui.argb
import com.htb.carplan.phone.ui.nodeState
import com.htb.carplan.phone.ui.railLine
import com.htb.carplan.phone.ui.speechBubble
import com.htb.carplan.shared.calendar.CategoryColors
import com.htb.carplan.shared.calendar.KoreanHolidays
import com.htb.carplan.shared.data.CalendarEvent
import com.htb.carplan.shared.model.BuiltInCategory
import com.htb.carplan.shared.model.Comment
import com.htb.carplan.shared.model.CustomCategory
import com.htb.carplan.shared.model.DayStatus
import com.htb.carplan.shared.model.Stop
import com.htb.carplan.shared.model.TripDay
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private val RAIL_W = 40.dp
private val NODE = 22.dp

/**
 * 그날 동선: the day on one rail (same drawing as the trip editor). A trip shows its stops as
 * nodes with the newest comment under each; plain events are nodes too. Tap a trip to edit it.
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
    tripComments: Map<String, List<Comment>> = emptyMap(),
    tripDays: Map<String, TripDay?> = emptyMap(),
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
                TripRail(
                    trip = trip,
                    stops = tripStops[trip.event.id].orEmpty(),
                    comments = tripComments[trip.event.id].orEmpty(),
                    day = tripDays[trip.event.id],
                    categories = categories,
                ) { onOpenTrip(trip) }
            }
            if (allDay.isNotEmpty()) item {
                Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    allDay.forEach { AllDayRow(it, categories) }
                }
            }
            itemsIndexed(route, key = { _, it -> it.groupId + it.event.id }) { i, item ->
                RouteStop(item, categories, isFirst = i == 0, isLast = i == route.lastIndex, drive = i > 0 && route[i - 1].event.place != null && item.event.place != null)
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

/** A trip on this day: title, then its stops on the rail with the latest comment under each. */
@Composable
private fun TripRail(trip: CalendarEvent, stops: List<Stop>, comments: List<Comment>, day: TripDay?, categories: List<CustomCategory>, onOpen: () -> Unit) {
    val color = argb(CategoryColors.of(trip.event.categoryId, categories))
    val driving = day?.status == DayStatus.DRIVING
    val nextId = if (driving) stops.firstOrNull { !it.visited }?.id else null
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
            Box(Modifier.width(3.dp).height(16.dp).background(color))
            Spacer(Modifier.width(8.dp))
            Text(trip.event.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.weight(1f))
            Text(
                when (day?.status) { DayStatus.DRIVING -> "가는 중 · 편집"; DayStatus.DONE -> "다녀옴 · 편집"; else -> "편집" },
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Teal,
            )
        }
        if (stops.isEmpty()) {
            Text("이날 장소 칸이 아직 없어요 · 눌러서 짜기", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(start = 11.dp))
        }
        stops.forEachIndexed { i, s ->
            val next = stops.getOrNull(i + 1)
            val fill by animateFloatAsState(if (next?.visited == true) 1f else 0f, tween(450), label = "fill")
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(
                    Modifier.width(RAIL_W).fillMaxHeight().railLine(NODE / 2 + 2.dp, drawAbove = i > 0, abovePassed = s.visited, belowFill = fill, drawBelow = i < stops.lastIndex),
                    contentAlignment = Alignment.TopCenter,
                ) { RailNode(nodeState(s.visited, s.id == nextId), NODE, Modifier.padding(top = 2.dp)) }
                Column(Modifier.weight(1f).padding(bottom = if (i < stops.lastIndex) 10.dp else 0.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        s.time?.let { Text(it, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (s.visited) Palette.TextSoft else Palette.ChipText, modifier = Modifier.width(52.dp)) }
                        Text(s.place.name, fontSize = 17.sp, fontWeight = if (s.id == nextId) FontWeight.Bold else FontWeight.Normal, color = if (s.visited) Palette.TextSoft else Palette.TextStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (s.id == nextId) Text("  다음", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.Teal)
                    }
                    if (s.memo.isNotBlank()) Text(s.memo, fontSize = 14.sp, color = Palette.ChipText, modifier = Modifier.padding(start = if (s.time != null) 52.dp else 0.dp))
                    comments.lastOrNull { it.stopId == s.id }?.let { c ->
                        Text(c.text, fontSize = 14.sp, color = Palette.TextStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, end = 12.dp).speechBubble().padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
        }
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

/** A plain event on the rail: node + time + title (+ place). The rail is dotted when the car drives between places. */
@Composable
private fun RouteStop(item: CalendarEvent, categories: List<CustomCategory>, isFirst: Boolean, isLast: Boolean, drive: Boolean) {
    val e = item.event
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            Modifier.width(RAIL_W).fillMaxHeight().railLine(NODE / 2 + 2.dp, drawAbove = !isFirst, abovePassed = false, belowFill = 0f, drawBelow = !isLast),
            contentAlignment = Alignment.TopCenter,
        ) { RailNode(NodeState.PLANNED, NODE, Modifier.padding(top = 2.dp), halo = false) }
        Column(Modifier.weight(1f).padding(bottom = if (isLast) 0.dp else 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.startTime ?: "종일", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.ChipText, modifier = Modifier.width(52.dp))
                Text(e.place?.name?.takeIf { it.isNotBlank() } ?: e.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
            }
            val meta = listOfNotNull(
                e.title.takeIf { e.place != null && it != e.place?.name },
                CategoryColors.label(e.categoryId, categories),
                e.reminderMinutes?.let { "알림 ${reminderLabel(it)}" },
                if (drive) "차로 이동" else null,
            ).joinToString("  ·  ")
            Text(meta, fontSize = 14.sp, color = Palette.TextSoft, modifier = Modifier.padding(start = 52.dp))
            if (e.memo.isNotBlank()) Text(e.memo, fontSize = 15.sp, color = Palette.ChipText, modifier = Modifier.padding(start = 52.dp, top = 2.dp))
            e.checklist.forEach { c -> Text((if (c.done) "✓ " else "– ") + c.text, fontSize = 15.sp, color = if (c.done) Palette.TextSoft else Palette.ChipText, modifier = Modifier.padding(start = 52.dp)) }
        }
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
