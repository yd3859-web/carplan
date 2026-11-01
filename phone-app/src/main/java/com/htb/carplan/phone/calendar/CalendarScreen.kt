package com.htb.carplan.phone.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.htb.carplan.phone.trip.TripDayScreen
import com.htb.carplan.phone.trip.TripRef
import com.htb.carplan.phone.ui.CenterSheet
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.phone.ui.argb
import com.htb.carplan.shared.calendar.CategoryColors
import com.htb.carplan.shared.calendar.KoreanHolidays
import com.htb.carplan.shared.data.CalendarEvent
import com.htb.carplan.shared.model.BuiltInCategory
import com.htb.carplan.shared.model.CustomCategory
import java.time.LocalDate
import java.time.YearMonth

private val DATE_AREA = 38.dp
private val BAR_HEIGHT = 20.dp
private val BAR_GAP = 3.dp
private val MARK_AREA = 18.dp
private const val MAX_LANES = 2
private val WEEKDAYS = listOf("일", "월", "화", "수", "목", "금", "토")
private val TripRefSaver = Saver<TripRef?, List<String>>(
    save = { it?.let { r -> listOf(r.groupId, r.eventId, r.date) } },
    restore = { TripRef(it[0], it[1], it[2]) },
)

/**
 * 2장 캘린더 메인 (A안): 오늘 카드 on top, a light month view below
 * (dots for one-day events, bars only for multi-day trips, a wrench for car care),
 * and tapping a day opens 그날 동선.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(vm: CalendarViewModel = viewModel()) {
    val month by vm.month.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val upcoming by vm.upcoming.collectAsStateWithLifecycle()
    // 2장 [알림] 칩: keep the phone's local reminders in step with the upcoming events.
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(upcoming) { com.htb.carplan.phone.push.Reminders.schedule(context, upcoming) }
    // 2장 Q05: Google Calendar events for the visible grid (only when turned on in 설정).
    androidx.compose.runtime.LaunchedEffect(month) { val g = MonthGrid.of(month); GoogleCalendar.refresh(context, g.start, g.endInclusive) }
    val car by vm.car.collectAsStateWithLifecycle()
    val categories by vm.customCategories.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val tripStops by vm.tripStops.collectAsStateWithLifecycle()
    val tripComments by vm.tripComments.collectAsStateWithLifecycle()
    val tripDays by vm.tripDays.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val lastGroupId by vm.lastGroupId.collectAsStateWithLifecycle()
    val commute by vm.commuteSuggestion.collectAsStateWithLifecycle()
    var daySheetOpen by remember { mutableStateOf(false) }
    var quickAddDate by remember { mutableStateOf<LocalDate?>(null) }
    var openTrip by rememberSaveable(stateSaver = TripRefSaver) { mutableStateOf<TripRef?>(null) }

    openTrip?.let { ref ->
        TripDayScreen(ref, onBack = { openTrip = null })
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
        val wide = maxWidth >= 720.dp
        Row(Modifier.fillMaxSize()) {
            // The month fills the height: week rows share the space left under the header (like the system calendar).
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(12.dp))
                TodayCard(upcoming, car, todayStops = if (selected == java.time.LocalDate.now()) tripStops else emptyMap())
                commute?.let { sg ->
                    // 16장 Q45: the records found a routine — one tap makes it a repeating 출퇴근 event.
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${sg.days.sortedBy { it.value }.joinToString("·") { it.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.KOREAN) }} ${sg.time}쯤 ${sg.place.name}에 ${sg.count}번 갔어요. 출근 일정으로 등록할까요?",
                            fontSize = 15.sp, color = Palette.TextStrong, modifier = Modifier.weight(1f),
                        )
                        androidx.compose.material3.TextButton(onClick = { vm.registerCommute(sg) }) { Text("등록", color = Palette.Navy, fontWeight = FontWeight.SemiBold) }
                        androidx.compose.material3.TextButton(onClick = vm::dismissCommute) { Text("아니요", color = Palette.TextSoft) }
                    }
                    androidx.compose.material3.HorizontalDivider(color = Palette.Line)
                }
                error?.let { Text("오류: $it", color = Palette.Sunday, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
                Spacer(Modifier.height(4.dp))
                MonthHeader(month, onPrev = { vm.showMonth(month.minusMonths(1)) }, onNext = { vm.showMonth(month.plusMonths(1)) })
                MonthGridView(month, selected, events, categories, Modifier.weight(1f)) { date ->
                    vm.select(date)
                    if (!wide) daySheetOpen = true
                }
                Spacer(Modifier.height(12.dp))
            }
            if (wide) {
                DayRoutePanel(
                    date = selected,
                    events = MonthGrid.eventsOn(selected, events),
                    categories = categories,
                    onAdd = { quickAddDate = selected },
                    modifier = Modifier.width(400.dp).fillMaxHeight().drawBehind { drawLine(Palette.Line, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) },
                    tripStops = tripStops,
                    tripComments = tripComments,
                    tripDays = tripDays,
                    onOpenTrip = { openTrip = TripRef(it.groupId, it.event.id, selected.toString()) },
                )
            }
        }
    }

    if (daySheetOpen) {
        CenterSheet(onDismiss = { daySheetOpen = false }) {
            DayRoutePanel(
                date = selected,
                events = MonthGrid.eventsOn(selected, events),
                categories = categories,
                onAdd = { daySheetOpen = false; quickAddDate = selected },
                modifier = Modifier.fillMaxWidth().height(480.dp),
                tripStops = tripStops,
                tripComments = tripComments,
                tripDays = tripDays,
                onOpenTrip = { daySheetOpen = false; openTrip = TripRef(it.groupId, it.event.id, selected.toString()) },
            )
        }
    }

    quickAddDate?.let { date ->
        QuickAddSheet(
            initialDate = date,
            categories = categories,
            onAddCategory = vm::addCategory,
            // [같이 갈 사람]: 나만, or one 묶음 (자주 타는 사람). Event-only groups are not picked here.
            groups = groups.filter { it.solo || it.isCircle },
            defaultGroupId = groups.firstOrNull { it.solo }?.id,
            onSave = { e, gid ->
                quickAddDate = null
                vm.addEvent(e, gid) { g, eid ->
                    // A new trip opens straight into its first day's stops (3장).
                    if (e.categoryId == BuiltInCategory.TRIP.id) openTrip = TripRef(g, eid, e.startDate)
                }
            },
            onDismiss = { quickAddDate = null },
        )
    }
}

@Composable
private fun MonthHeader(month: YearMonth, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val title = if (month.year == YearMonth.now().year) "${month.monthValue}월" else "${month.year}년 ${month.monthValue}월"
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.weight(1f))
        IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 달", tint = Palette.TextSoft) }
        IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 달", tint = Palette.TextSoft) }
    }
}

@Composable
private fun MonthGridView(
    month: YearMonth,
    selected: LocalDate,
    events: List<CalendarEvent>,
    categories: List<CustomCategory>,
    modifier: Modifier = Modifier,
    onDayClick: (LocalDate) -> Unit,
) {
    val multiDay = events.filter { it.event.endDate.isNotEmpty() && it.event.endDate != it.event.startDate }
    val singleDay = events - multiDay.toSet()
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            WEEKDAYS.forEachIndexed { i, d -> Text(d, Modifier.weight(1f), textAlign = TextAlign.Start, color = when (i) { 0 -> Palette.Sunday; 6 -> Palette.Saturday; else -> Palette.TextSoft }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
        MonthGrid.weeks(month).forEach { weekStart ->
            HorizontalDivider(color = Palette.Line)
            WeekRow(month, weekStart, selected, multiDay, singleDay, categories, Modifier.weight(1f), onDayClick)
        }
    }
}

@Composable
private fun WeekRow(
    month: YearMonth,
    weekStart: LocalDate,
    selected: LocalDate,
    multiDay: List<CalendarEvent>,
    singleDay: List<CalendarEvent>,
    categories: List<CustomCategory>,
    modifier: Modifier,
    onDayClick: (LocalDate) -> Unit,
) {
    val bars = MonthGrid.bars(weekStart, multiDay)
    val lanes = minOf(MAX_LANES, (bars.maxOfOrNull { it.lane } ?: -1) + 1)
    val barsHeight = (BAR_HEIGHT + BAR_GAP) * lanes
    BoxWithConstraints(modifier.fillMaxWidth().heightIn(min = DATE_AREA + barsHeight + MARK_AREA + 6.dp)) {
        val colW: Dp = maxWidth / 7
        Row(Modifier.fillMaxSize()) {
            (0..6).forEach { i ->
                val date = weekStart.plusDays(i.toLong())
                DayCell(
                    date = date,
                    inMonth = YearMonth.from(date) == month,
                    selected = date == selected,
                    marks = MonthGrid.eventsOn(date, singleDay),
                    hiddenBars = bars.count { it.lane >= MAX_LANES && i in it.startCol..it.endCol },
                    barsHeight = barsHeight,
                    categories = categories,
                    modifier = Modifier.weight(1f).fillMaxHeight().clickable { onDayClick(date) },
                )
            }
        }
        bars.filter { it.lane < MAX_LANES }.forEach { bar ->
            val color = argb(CategoryColors.of(bar.item.event.categoryId, categories))
            TripBar(
                label = bar.item.event.title,
                color = color,
                modifier = Modifier
                    .offset(x = colW * bar.startCol + 2.dp, y = DATE_AREA + (BAR_HEIGHT + BAR_GAP) * bar.lane)
                    .width(colW * (bar.endCol - bar.startCol + 1) - 4.dp)
                    .height(BAR_HEIGHT),
            )
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    selected: Boolean,
    marks: List<CalendarEvent>,
    hiddenBars: Int,
    barsHeight: Dp,
    categories: List<CustomCategory>,
    modifier: Modifier,
) {
    val holiday = KoreanHolidays.nameOf(date)
    val isToday = date == LocalDate.now()
    val base = when {
        holiday != null || MonthGrid.isSunday(date) -> Palette.Sunday
        MonthGrid.isSaturday(date) -> Palette.Saturday
        else -> Palette.TextStrong
    }
    Column(modifier.padding(horizontal = 2.dp), horizontalAlignment = Alignment.Start) {
        Box(
            Modifier.padding(top = 5.dp, start = 2.dp).size(30.dp).clip(Corner).background(
                when {
                    isToday -> Palette.Navy
                    selected -> Palette.InputBg
                    else -> Color.Transparent
                },
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${date.dayOfMonth}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                color = when {
                    isToday -> Color.White
                    inMonth -> base
                    else -> base.copy(alpha = 0.3f)
                },
            )
        }
        Spacer(Modifier.height(DATE_AREA - 35.dp + barsHeight))
        DayMarks(marks, hiddenBars, categories, holiday?.takeIf { inMonth })
    }
}

/** Dots for one-day events (accent when it has a place = the car goes there), a wrench for car care. */
@Composable
private fun DayMarks(marks: List<CalendarEvent>, hiddenBars: Int, categories: List<CustomCategory>, holiday: String?) {
    Row(Modifier.height(MARK_AREA).padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (marks.any { it.event.categoryId == BuiltInCategory.CAR.id }) Icon(Icons.Filled.Build, "차량 관리", tint = Palette.ChipText, modifier = Modifier.size(12.dp))
        marks.filter { it.event.categoryId != BuiltInCategory.CAR.id }.take(3).forEach {
            val c = if (it.event.place != null) Palette.Teal else argb(CategoryColors.of(it.event.categoryId, categories))
            Box(Modifier.size(7.dp).clip(CircleShape).background(c))
        }
        val more = marks.size - 3 + hiddenBars
        if (more > 0) Text("+$more", fontSize = 13.sp, color = Palette.TextSoft)
        if (marks.isEmpty() && hiddenBars == 0 && holiday != null) Text(holiday, fontSize = 13.sp, color = Palette.Sunday, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

/** A multi-day trip as one continuous bar. */
@Composable
private fun TripBar(label: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.clip(Corner).background(color.copy(alpha = 0.16f)).drawBehind { drawRect(color, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }, contentAlignment = Alignment.CenterStart) {
        Text(label, Modifier.padding(start = 8.dp, end = 4.dp), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
