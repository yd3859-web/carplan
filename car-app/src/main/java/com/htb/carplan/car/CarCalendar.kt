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
import androidx.compose.ui.draw.clip
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Create
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import java.time.format.TextStyle
import java.util.Locale

/** 주차 중 차 화면의 탭 (정원 2026-10-31: 폰 앱 기능을 차에도). Driving shows only [TODAY], no bar. */
enum class CarTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    TODAY("오늘", Icons.Filled.Home),
    CALENDAR("캘린더", Icons.Filled.DateRange),
    PEOPLE("같이 탈 사람", Icons.Filled.Person),
    RECORDS("기록", Icons.Filled.Create),
    VEHICLE("차량", Icons.Filled.Build),
}

private val Corner = RoundedCornerShape(4.dp)

/** Counts taps on the tab that is already showing (CarTabBar). */
val LocalCarTabTapAgain = androidx.compose.runtime.compositionLocalOf { 0 }

/**
 * Tapping the tab you are on brings it back to its start (폰 손③과 같음): the calendar to today, the records to the
 * newest day. Taps counted before this tab showed are not replayed.
 */
@Composable
fun OnCarTabTapAgain(action: suspend () -> Unit) {
    val n = LocalCarTabTapAgain.current
    val start = remember { n }
    androidx.compose.runtime.LaunchedEffect(n) { if (n != start) action() }
}

/**
 * Swipe sideways for the next / previous month (폰 캘린더와 같음, the arrow buttons still work). Parked only — the
 * calendar and records tabs do not exist while driving.
 */
@Composable
fun Modifier.monthSwipe(key: Any, onPrev: () -> Unit, onNext: () -> Unit): Modifier {
    // Pleos: touch answers with a vibration — a tick when the month turns.
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    return pointerInput(key) {
        var total = 0f
        val threshold = 80.dp.toPx()
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                if (total < -threshold) { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick); onNext() }
                else if (total > threshold) { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick); onPrev() }
            },
        ) { change, dx -> total += dx; change.consume() }
    }
}

/**
 * The tab bar (정원 2026-10-31, 넷플릭스 하단 바 참고): one rounded bar floating at the bottom centre,
 * icon over label, the chosen tab on a grey rounded patch. The bar is the one place that is round —
 * it reads as "move between screens", not as content.
 */
@Composable
fun CarTabBar(tab: CarTab, onTab: (CarTab) -> Unit, modifier: Modifier = Modifier) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val c = LocalCarColors.current
    // Same colours as the rest of the screen (Pleos light/dark): a white bar by day, the popup grey by night.
    val bar = if (c.dark) c.sheet else c.bg
    val chosen = if (c.dark) c.sheetEdge else c.sheet
    // The grey patch slides to the chosen tab (정원 2026-10-31: 순간이동 말고 옆으로 흐르게). Tabs are one width so it can.
    // Five tabs: they must fit the half screen too (≈ 830 dp).
    val tabW = if (LocalCompact.current) 148.dp else 200.dp
    val gap = 4.dp
    val x by androidx.compose.animation.core.animateDpAsState((tabW + gap) * tab.ordinal, androidx.compose.animation.core.tween(TAB_SLIDE_MS), label = "tab")
    Box(
        modifier
            .shadow(12.dp, RoundedCornerShape(50))
            .background(bar, RoundedCornerShape(50))
            .border(1.dp, c.sheetEdge, RoundedCornerShape(50))
            .padding(8.dp),
    ) {
        // Read in the layout phase: the slide moves the patch without recomposing the bar each frame.
        Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(x.roundToPx(), 0) }.width(tabW).height(100.dp).background(chosen, RoundedCornerShape(50)))
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            CarTab.entries.forEach { t ->
                val on = t == tab
                Column(
                    Modifier.width(tabW).height(100.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick); onTab(t) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    androidx.compose.material3.Icon(t.icon, contentDescription = null, tint = if (on) c.ink else c.soft, modifier = Modifier.size(42.dp))
                    Text(t.label, color = if (on) c.ink else c.soft, fontSize = if (LocalCompact.current) 20.sp else 24.sp, maxLines = 1, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

/** One length for the bar patch and the screen slide, so both move together. */
const val TAB_SLIDE_MS = 300

/**
 * 캘린더 탭: the month on the left (each day shows its first event or "n개"), the chosen day on the right with
 * [여기로 안내] for today's places and [이 날 일정 추가]. Same data as the phone calendar (shared CalendarRepository).
 */
@Composable
fun RowScope.CarCalendar(vm: CarHomeViewModel, onAdd: (LocalDate) -> Unit, onGoTo: (CalendarEvent) -> Unit) {
    val month by vm.calMonth.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    var picked by remember { mutableStateOf(today) }
    OnCarTabTapAgain { vm.calMonth.value = java.time.YearMonth.now(); picked = today }
    // Paging months moves the chosen day along (else the right side would show a day from another month, half-loaded).
    androidx.compose.runtime.LaunchedEffect(month) {
        if (java.time.YearMonth.from(picked) != month) picked = if (java.time.YearMonth.from(today) == month) today else month.atDay(1)
    }
    val c = LocalCarColors.current
    // Wide: month left, the day right. Half screen (next to the navigation): month on top, the day below.
    if (!LocalCompact.current) {
        MonthBlock(vm, picked, { picked = it }, Modifier.weight(1.35f).fillMaxHeight().padding(horizontal = 28.dp, vertical = 20.dp))
        VerticalDivider(color = c.line)
        DayBlock(vm, picked, onAdd, onGoTo, Modifier.weight(1f).fillMaxHeight().padding(horizontal = 28.dp, vertical = 20.dp))
    } else Column(Modifier.weight(1f).fillMaxHeight()) {
        MonthBlock(vm, picked, { picked = it }, Modifier.weight(1.15f).padding(horizontal = 20.dp, vertical = 12.dp))
        HorizontalDivider(color = c.line)
        DayBlock(vm, picked, onAdd, onGoTo, Modifier.weight(1f).padding(horizontal = 20.dp, vertical = 12.dp))
    }
}

@Composable
private fun MonthBlock(vm: CarHomeViewModel, picked: LocalDate, onPick: (LocalDate) -> Unit, modifier: Modifier) {
    val c = LocalCarColors.current
    val compact = LocalCompact.current
    val month by vm.calMonth.collectAsStateWithLifecycle()
    val events by vm.monthEvents.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { vm.calMonth.value = month.minusMonths(1) }, shape = Corner, modifier = Modifier.heightIn(min = 76.dp).widthIn(min = 76.dp), border = androidx.compose.foundation.BorderStroke(1.dp, c.line)) {
                Text(if (compact) "‹" else "‹ 이전", color = c.ink, fontSize = 24.sp)
            }
            Text(if (compact) "${month.monthValue}월" else "${month.year}년 ${month.monthValue}월", color = c.ink, fontSize = if (compact) 32.sp else 36.sp, maxLines = 1, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { vm.calMonth.value = month.plusMonths(1) }, shape = Corner, modifier = Modifier.heightIn(min = 76.dp).widthIn(min = 76.dp), border = androidx.compose.foundation.BorderStroke(1.dp, c.line)) {
                Text(if (compact) "›" else "다음 ›", color = c.ink, fontSize = 24.sp)
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { vm.calMonth.value = java.time.YearMonth.now(); onPick(today) }, shape = Corner, modifier = Modifier.heightIn(min = 76.dp), border = androidx.compose.foundation.BorderStroke(1.dp, c.line)) {
                Text("오늘", color = c.ink, fontSize = 24.sp)
            }
        }
        // What the colours mean (정원 2026-10-31: 개수 대신 무슨 일정인지 — 여행과 차량 관리는 다른 색).
        if (!compact) Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
        val motion = LocalMotion.current
        androidx.compose.animation.AnimatedContent(
            targetState = month,
            modifier = Modifier.weight(1f).monthSwipe(month, onPrev = { vm.calMonth.value = month.minusMonths(1) }, onNext = { vm.calMonth.value = month.plusMonths(1) }),
            label = "month",
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                if (!motion) (androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None)
                else (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(MOTION_MS)) { w -> dir * w / 3 } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(MOTION_MS))) togetherWith
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(MOTION_MS / 2))
            },
        ) { month ->
        // Each day's events once per month (not 42 filters on every draw).
        val byDay = remember(events, month) {
            (1..month.lengthOfMonth()).associate { n -> month.atDay(n).let { d -> d to vm.eventsOn(d, events).filterNot { vm.isTestEvent(it.event) } } }
        }
        val first = month.atDay(1)
        val lead = first.dayOfWeek.value % 7 // Sunday first
        val days = month.lengthOfMonth()
        val rows = (lead + days + 6) / 7
        Column(Modifier.fillMaxSize()) {
            for (r in 0 until rows) {
                Row(Modifier.weight(1f)) {
                    for (col in 0 until 7) {
                        val n = r * 7 + col - lead + 1
                        Box(Modifier.weight(1f).fillMaxHeight().padding(2.dp)) {
                            if (n in 1..days) {
                                val d = month.atDay(n)
                                // What the user registered shows, repeating ones too; the 시연 메뉴 test commute doesn't (정원 2026-10-31).
                                val dayEvents = byDay[d].orEmpty()
                                val isPicked = d == picked
                                // The chosen day fades to ink instead of flipping (정원 2026-10-31: 부드럽게).
                                val cellBg by carColor(if (isPicked) c.ink else c.bg, "cellBg")
                                val onCell by carColor(if (isPicked) c.onInk else c.ink, "cellText")
                                Column(
                                    Modifier.fillMaxSize()
                                        .background(cellBg, Corner)
                                        .then(if (d == today && !isPicked) Modifier.border(2.dp, c.ink, Corner) else Modifier)
                                        .clickable { onPick(d) }
                                        .padding(if (compact) 4.dp else 8.dp),
                                ) {
                                    Text("$n", color = if (isPicked) onCell else if (col == 0) c.warn else onCell, fontSize = 24.sp, fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal)
                                    // Up to three events as "colour bar + name"; the rest as "+n".
                                    // Half screen: a cell is too narrow for names — one colour bar per event, the names are below.
                                    if (compact) dayEvents.take(3).forEach { e ->
                                        Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(6.dp).background(Color(CategoryColors.of(e.event.categoryId, categories))))
                                    } else dayEvents.take(3).forEach { e ->
                                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.width(8.dp).height(24.dp).background(Color(CategoryColors.of(e.event.categoryId, categories))))
                                            Text(e.event.title, color = onCell, fontSize = 20.sp, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Clip, modifier = Modifier.padding(start = 6.dp))
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
    }
}

/** The chosen day: what is on it, and the two things the car can do with it. */
@Composable
private fun DayBlock(vm: CarHomeViewModel, picked: LocalDate, onAdd: (LocalDate) -> Unit, onGoTo: (CalendarEvent) -> Unit, modifier: Modifier) {
    val c = LocalCarColors.current
    val compact = LocalCompact.current
    val events by vm.monthEvents.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    Column(modifier) {
        Text("${picked.monthValue}월 ${picked.dayOfMonth}일 (${picked.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})", color = c.ink, fontSize = if (compact) 28.sp else 32.sp, fontWeight = FontWeight.Bold)
        // Each half of the fade draws its own day (the old day fades out while the new one fades in).
        androidx.compose.animation.Crossfade(picked, Modifier.weight(1f), animationSpec = carSpec(), label = "day") { day ->
        val dayEvents = vm.eventsOn(day, events).filterNot { vm.isTestEvent(it.event) }
        Column(Modifier.fillMaxSize().padding(top = if (compact) 4.dp else 12.dp).verticalScroll(rememberScrollState())) {
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
                    if (day == today && e.place != null && e.categoryId != BuiltInCategory.TRIP.id) {
                        OutlinedButton(onClick = { onGoTo(item) }, shape = Corner, modifier = Modifier.heightIn(min = 76.dp), border = androidx.compose.foundation.BorderStroke(1.dp, c.line)) {
                            Text("안내", color = c.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                HorizontalDivider(color = c.line)
            }
        }
        }
        Button(
            onClick = { onAdd(picked) }, modifier = Modifier.fillMaxWidth().heightIn(min = if (compact) 76.dp else 96.dp), shape = Corner,
            colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.onInk),
        ) { Text("이 날 일정 추가", fontSize = 28.sp, fontWeight = FontWeight.Bold) }
    
    }
}
