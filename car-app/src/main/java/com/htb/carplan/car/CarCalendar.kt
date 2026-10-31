package com.htb.carplan.car

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htb.carplan.shared.data.CalendarEvent
import com.htb.carplan.shared.model.BuiltInCategory
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** 주차 중 차 화면의 탭 (정원 2026-10-31: 폰 앱 기능을 차에도). Driving shows only [TODAY]. */
enum class CarTab(val label: String) { TODAY("오늘"), CALENDAR("캘린더") }

private val Corner = RoundedCornerShape(4.dp)

/** Driver-side tab rail: big text tabs, the chosen one bold with a bar (no colour: blue is only for where the car goes). */
@Composable
fun TabRail(tab: CarTab, onTab: (CarTab) -> Unit) {
    val c = LocalCarColors.current
    Row(Modifier.fillMaxHeight()) {
        Column(Modifier.width(150.dp).fillMaxHeight().padding(top = 12.dp)) {
            CarTab.entries.forEach { t ->
                val on = t == tab
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 96.dp).clickable { onTab(t) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(6.dp).height(48.dp).background(if (on) c.ink else c.bg))
                    Text(t.label, color = if (on) c.ink else c.soft, fontSize = 26.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(start = 22.dp))
                }
            }
        }
        VerticalDivider(color = c.line)
    }
}

/**
 * 캘린더 탭: the month on the left (each day shows its first event or "n개"), the chosen day on the right with
 * [여기로 안내] for today's places and [이 날 일정 추가]. Same data as the phone calendar (shared CalendarRepository).
 */
@Composable
fun RowScope.CarCalendar(vm: CarHomeViewModel, onAdd: (LocalDate) -> Unit, onGoTo: (CalendarEvent) -> Unit) {
    val c = LocalCarColors.current
    val month by vm.calMonth.collectAsStateWithLifecycle()
    val events by vm.monthEvents.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    var picked by remember { mutableStateOf(today) }
    // Paging months moves the chosen day along (else the right side would show a day from another month, half-loaded).
    androidx.compose.runtime.LaunchedEffect(month) {
        if (java.time.YearMonth.from(picked) != month) picked = if (java.time.YearMonth.from(today) == month) today else month.atDay(1)
    }

    Column(Modifier.weight(1.35f).fillMaxHeight().padding(horizontal = 28.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { vm.calMonth.value = month.minusMonths(1) }, shape = Corner, modifier = Modifier.heightIn(min = 76.dp), border = androidx.compose.foundation.BorderStroke(1.dp, c.line)) {
                Text("‹ 이전", color = c.ink, fontSize = 24.sp)
            }
            Text("${month.year}년 ${month.monthValue}월", color = c.ink, fontSize = 36.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { vm.calMonth.value = month.plusMonths(1) }, shape = Corner, modifier = Modifier.heightIn(min = 76.dp), border = androidx.compose.foundation.BorderStroke(1.dp, c.line)) {
                Text("다음 ›", color = c.ink, fontSize = 24.sp)
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { vm.calMonth.value = java.time.YearMonth.now(); picked = today }, shape = Corner, modifier = Modifier.heightIn(min = 76.dp), border = androidx.compose.foundation.BorderStroke(1.dp, c.line)) {
                Text("오늘", color = c.ink, fontSize = 24.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row {
            listOf("일", "월", "화", "수", "목", "금", "토").forEach { d ->
                Text(d, color = c.soft, fontSize = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        HorizontalDivider(color = c.line, modifier = Modifier.padding(top = 8.dp))
        val first = month.atDay(1)
        val lead = first.dayOfWeek.value % 7 // Sunday first
        val days = month.lengthOfMonth()
        val rows = (lead + days + 6) / 7
        Column(Modifier.weight(1f)) {
            for (r in 0 until rows) {
                Row(Modifier.weight(1f)) {
                    for (col in 0 until 7) {
                        val n = r * 7 + col - lead + 1
                        Box(Modifier.weight(1f).fillMaxHeight().padding(2.dp)) {
                            if (n in 1..days) {
                                val d = month.atDay(n)
                                val dayEvents = vm.eventsOn(d, events)
                                val isPicked = d == picked
                                Column(
                                    Modifier.fillMaxSize()
                                        .background(if (isPicked) c.ink else c.bg, Corner)
                                        .then(if (d == today && !isPicked) Modifier.border(2.dp, c.ink, Corner) else Modifier)
                                        .clickable { picked = d }
                                        .padding(8.dp),
                                ) {
                                    Text("$n", color = if (isPicked) c.onInk else if (col == 0) c.warn else c.ink, fontSize = 24.sp, fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal)
                                    when {
                                        dayEvents.size == 1 -> Text(dayEvents.first().event.title, color = if (isPicked) c.onInk else c.soft, fontSize = 20.sp, maxLines = 1)
                                        dayEvents.size > 1 -> Text("${dayEvents.size}개", color = if (isPicked) c.onInk else c.soft, fontSize = 20.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    VerticalDivider(color = c.line)
    // The chosen day: what is on it, and the two things the car can do with it.
    Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 28.dp, vertical = 20.dp)) {
        Text("${picked.monthValue}월 ${picked.dayOfMonth}일 (${picked.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})", color = c.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        val dayEvents = vm.eventsOn(picked, events)
        Column(Modifier.weight(1f).padding(top = 12.dp).verticalScroll(rememberScrollState())) {
            if (dayEvents.isEmpty()) Text("일정이 없어요", color = c.soft, fontSize = 26.sp, modifier = Modifier.padding(top = 12.dp))
            dayEvents.forEach { item ->
                val e = item.event
                val kind = when (e.categoryId) { BuiltInCategory.TRIP.id -> "여행"; BuiltInCategory.CAR.id -> "차량 관리"; else -> null }
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(e.startTime ?: "종일", color = c.soft, fontSize = 24.sp, modifier = Modifier.width(96.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, color = c.ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        listOfNotNull(kind, e.place?.name).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { Text(it, color = c.soft, fontSize = 20.sp, maxLines = 1) }
                    }
                    // Only today's places can be driven to from here; a trip runs through the 오늘 tab.
                    if (picked == today && e.place != null && e.categoryId != BuiltInCategory.TRIP.id) {
                        OutlinedButton(onClick = { onGoTo(item) }, shape = Corner, modifier = Modifier.heightIn(min = 76.dp), border = androidx.compose.foundation.BorderStroke(1.dp, c.line)) {
                            Text("안내", color = c.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                HorizontalDivider(color = c.line)
            }
        }
        Button(
            onClick = { onAdd(picked) }, modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp), shape = Corner,
            colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.onInk),
        ) { Text("이 날 일정 추가", fontSize = 28.sp, fontWeight = FontWeight.Bold) }
    }
}
