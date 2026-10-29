package com.htb.carplan.phone.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

/** 오늘 카드: where the car goes next, how the car is, and the next car-care item. */
@Composable
fun TodayCard(upcoming: List<CalendarEvent>, car: Car?, modifier: Modifier = Modifier) {
    val today = LocalDate.now()
    val next = nextDestination(upcoming, today)
    val care = upcoming
        .filter { it.event.categoryId == BuiltInCategory.CAR.id && LocalDate.parse(it.event.startDate) >= today }
        .minByOrNull { it.event.startDate }

    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Palette.Navy, Palette.NavySoft)))
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("오늘  ${dayTitle(today)}", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        if (next != null) {
            val e = next.event
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("다음 ▶ ", color = Palette.Teal, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(e.place?.name?.takeIf { it.isNotBlank() } ?: e.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
                e.startTime?.let { Text("  $it", color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp) }
            }
            if (e.place != null && e.title != e.place?.name) Text(e.title, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        } else {
            Text("오늘은 차로 갈 일정이 없어요", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(carLine(car), color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
        care?.let {
            val days = ChronoUnit.DAYS.between(today, LocalDate.parse(it.event.startDate))
            val what = it.event.maintenance?.item?.label ?: it.event.title
            Text("🔧 $what " + if (days == 0L) "오늘" else "${days}일 남음", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
        }
    }
}

/** Today's next event with a place (or any timed event), skipping ones already past. */
private fun nextDestination(upcoming: List<CalendarEvent>, today: LocalDate): CalendarEvent? {
    val now = LocalTime.now().toString().take(5)
    return MonthGrid.eventsOn(today, upcoming)
        .filter { it.event.categoryId != BuiltInCategory.CAR.id }
        .filter { it.event.startTime == null || it.event.startTime!! >= now }
        .sortedWith(compareBy({ it.event.place == null }, { it.event.startTime ?: "99" }))
        .firstOrNull()
}

private fun carLine(car: Car?): String {
    val s = car?.state ?: return "🚗 차를 연결하면 연료·주행가능거리가 보여요"
    val parts = listOfNotNull(
        s.fuelPercent?.let { "연료 ${it.toInt()}%" },
        s.rangeKm?.let { "${it.toInt()}km 갈 수 있어요" },
    )
    return "🚗 " + if (parts.isEmpty()) "차 연결됨" else parts.joinToString(" · ")
}
