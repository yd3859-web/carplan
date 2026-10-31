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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.shadow
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
import com.htb.carplan.shared.calendar.CategoryColors
import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import java.time.format.TextStyle
import java.util.Locale

/** 주차 중 차 화면의 탭 (정원 2026-10-31: 폰 앱 기능을 차에도). Driving shows only [TODAY], no bar. */
enum class CarTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    TODAY("오늘", Icons.Filled.Home),
    CALENDAR("캘린더", Icons.Filled.DateRange),
    PEOPLE("같이 탈 사람", Icons.Filled.Person),
}

private val Corner = RoundedCornerShape(4.dp)

/**
 * The tab bar (정원 2026-10-31, 넷플릭스 하단 바 참고): one rounded bar floating at the bottom centre,
 * icon over label, the chosen tab on a grey rounded patch. The bar is the one place that is round —
 * it reads as "move between screens", not as content.
 */
@Composable
fun CarTabBar(tab: CarTab, onTab: (CarTab) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalCarColors.current
    // Same colours as the rest of the screen (Pleos light/dark): a white bar by day, the popup grey by night.
    val bar = if (c.dark) c.sheet else c.bg
    val chosen = if (c.dark) c.sheetEdge else c.sheet
    Row(
        modifier
            .shadow(12.dp, RoundedCornerShape(50))
            .background(bar, RoundedCornerShape(50))
            .border(1.dp, c.sheetEdge, RoundedCornerShape(50))
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CarTab.entries.forEach { t ->
            val on = t == tab
            Column(
                Modifier.widthIn(min = 200.dp).heightIn(min = 100.dp)
                    .background(if (on) chosen else bar, RoundedCornerShape(50))
                    .clickable { onTab(t) }
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                androidx.compose.material3.Icon(t.icon, contentDescription = null, tint = if (on) c.ink else c.soft, modifier = Modifier.size(42.dp))
                Text(t.label, color = if (on) c.ink else c.soft, fontSize = 24.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
            }
        }
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
    val categories by vm.categories.collectAsStateWithLifecycle()
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
        // What the colours mean (정원 2026-10-31: 개수 대신 무슨 일정인지 — 여행과 차량 관리는 다른 색).
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            (BuiltInCategory.entries.map { it.id } + categories.map { it.id }).forEach { id ->
                Box(Modifier.width(8.dp).height(24.dp).background(Color(CategoryColors.of(id, categories))))
                Text(CategoryColors.label(id, categories), color = c.soft, fontSize = 20.sp, modifier = Modifier.padding(start = 8.dp, end = 20.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
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
                                // What the user registered shows, repeating ones too; the 시연 메뉴 test commute doesn't (정원 2026-10-31).
                                val dayEvents = vm.eventsOn(d, events).filterNot { vm.isTestEvent(it.event) }
                                val isPicked = d == picked
                                Column(
                                    Modifier.fillMaxSize()
                                        .background(if (isPicked) c.ink else c.bg, Corner)
                                        .then(if (d == today && !isPicked) Modifier.border(2.dp, c.ink, Corner) else Modifier)
                                        .clickable { picked = d }
                                        .padding(8.dp),
                                ) {
                                    Text("$n", color = if (isPicked) c.onInk else if (col == 0) c.warn else c.ink, fontSize = 24.sp, fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal)
                                    // Up to three events as "colour bar + name"; the rest as "+n".
                                    dayEvents.take(3).forEach { e ->
                                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.width(8.dp).height(24.dp).background(Color(CategoryColors.of(e.event.categoryId, categories))))
                                            Text(e.event.title, color = if (isPicked) c.onInk else c.ink, fontSize = 20.sp, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Clip, modifier = Modifier.padding(start = 6.dp))
                                        }
                                    }
                                    if (dayEvents.size > 3) Text("+${dayEvents.size - 3}", color = if (isPicked) c.onInk else c.soft, fontSize = 20.sp)
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
        val dayEvents = vm.eventsOn(picked, events).filterNot { vm.isTestEvent(it.event) }
        Column(Modifier.weight(1f).padding(top = 12.dp).verticalScroll(rememberScrollState())) {
            if (dayEvents.isEmpty()) Text("일정이 없어요", color = c.soft, fontSize = 26.sp, modifier = Modifier.padding(top = 12.dp))
            dayEvents.forEach { item ->
                val e = item.event
                val kind = CategoryColors.label(e.categoryId, categories)
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.padding(end = 12.dp).width(6.dp).height(56.dp).background(Color(CategoryColors.of(e.categoryId, categories))))
                    Text(e.startTime ?: "종일", color = c.soft, fontSize = 24.sp, modifier = Modifier.width(96.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, color = c.ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        listOfNotNull(kind, e.place?.name?.ifBlank { null }).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { Text(it, color = c.soft, fontSize = 20.sp, maxLines = 1) }
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
