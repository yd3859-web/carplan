package com.htb.carplan.phone.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.shared.data.CalendarEvent
import com.htb.carplan.shared.model.BuiltInCategory
import com.htb.carplan.shared.model.Car
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** 오늘: where the car goes next, how the car is, and the next car-care item. A flat header, not a box. */
@Composable
fun TodayCard(upcoming: List<CalendarEvent>, car: Car?, modifier: Modifier = Modifier, todayStops: Map<String, List<com.htb.carplan.shared.model.Stop>> = emptyMap()) {
    val today = LocalDate.now()
    // A trip today: its next unvisited stop is "다음". Otherwise the next event with a place.
    val tripNext = todayStops.entries.firstNotNullOfOrNull { (eid, stops) ->
        stops.filter { !it.deleted }.firstOrNull { !it.visited }?.let { s -> upcoming.firstOrNull { it.event.id == eid }?.let { ce -> ce to s } }
    }
    val next = tripNext?.let { (ce, s) -> CalendarEvent(ce.groupId, ce.event.copy(title = ce.event.title, place = s.place, startTime = s.time)) } ?: nextDestination(upcoming, today)
    // Only what is *due* (due-*), not a record someone just logged.
    val care = upcoming
        .filter { it.event.categoryId == BuiltInCategory.CAR.id && it.event.id.startsWith("due-") && LocalDate.parse(it.event.startDate) >= today }
        .minByOrNull { it.event.startDate }

    Column(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("오늘  ${dayTitle(today)}", color = Palette.TextSoft, fontSize = 15.sp)
        if (next != null) {
            val e = next.event
            Row(verticalAlignment = Alignment.Bottom) {
                Text("다음", color = Palette.Teal, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp, bottom = 5.dp))
                Text(e.place?.name?.takeIf { it.isNotBlank() } ?: e.title, color = Palette.TextStrong, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
                e.startTime?.let { Text("  $it", color = Palette.TextStrong, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 3.dp)) }
            }
            if (e.place != null && e.title != e.place?.name) Text(e.title, color = Palette.TextSoft, fontSize = 15.sp)
        } else {
            Text("오늘은 차로 갈 일정이 없어요", color = Palette.TextStrong, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            listOfNotNull(carLine(car), care?.let { careLine(it, today) }).joinToString("    ·    "),
            color = Palette.ChipText, fontSize = 15.sp,
        )
    }
    HorizontalDivider(color = Palette.Line)
}

private fun careLine(it: CalendarEvent, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(today, LocalDate.parse(it.event.startDate))
    val what = it.event.maintenance?.item?.label ?: it.event.title
    return "정비  $what " + if (days == 0L) "오늘" else "${days}일 남음"
}

/** Today's next event with a place (or any timed event), skipping ones already past. */
private fun nextDestination(upcoming: List<CalendarEvent>, today: LocalDate): CalendarEvent? {
    val now = LocalTime.now().toString().take(5)
    return MonthGrid.eventsOn(today, upcoming)
        .filter { it.event.categoryId != BuiltInCategory.CAR.id }
        // A trip is a list of stops (shown on the rail), not a destination itself; the car asks about its stops.
        .filter { it.event.categoryId != BuiltInCategory.TRIP.id || it.event.place != null }
        .filter { it.event.startTime == null || it.event.startTime!! >= now }
        .sortedWith(compareBy({ it.event.place == null }, { it.event.startTime ?: "99" }))
        .firstOrNull()
}

private fun carLine(car: Car?): String {
    val s = car?.state ?: return "차를 연결하면 연료와 주행가능거리가 보여요"
    val parts = listOfNotNull(
        s.fuelPercent?.let { "연료 ${it.toInt()}%" },
        s.rangeKm?.takeIf { it > 0 }?.let { "주행가능 ${it.toInt()}km" },
    )
    return if (parts.isEmpty()) "차 연결됨" else parts.joinToString(" · ")
}
