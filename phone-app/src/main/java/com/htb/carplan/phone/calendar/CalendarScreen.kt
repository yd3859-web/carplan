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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.phone.ui.argb
import com.htb.carplan.shared.calendar.CategoryColors
import com.htb.carplan.shared.calendar.KoreanHolidays
import com.htb.carplan.shared.data.CalendarEvent
import com.htb.carplan.shared.model.CustomCategory
import java.time.LocalDate
import java.time.YearMonth

private val DATE_AREA = 34.dp
private val BAR_HEIGHT = 18.dp
private val BAR_GAP = 3.dp
private const val MAX_LANES = 3
private val WEEKDAYS = listOf("일", "월", "화", "수", "목", "금", "토")

/** 2장 캘린더 메인. Wide screens get the day panel on the right like the example; phones get a sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(vm: CalendarViewModel = viewModel()) {
    val month by vm.month.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val categories by vm.customCategories.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var daySheetOpen by remember { mutableStateOf(false) }
    var quickAddDate by remember { mutableStateOf<LocalDate?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
        val wide = maxWidth >= 720.dp
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                MonthHeader(month, onPrev = { vm.showMonth(month.minusMonths(1)) }, onNext = { vm.showMonth(month.plusMonths(1)) })
                error?.let { Text("오류: $it", color = Palette.Sunday, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
                MonthGridView(month, selected, events, categories) { date ->
                    vm.select(date)
                    if (!wide) daySheetOpen = true
                }
                Spacer(Modifier.height(110.dp)) // room for the floating tab bar
            }
            if (wide) {
                DayPanel(
                    date = selected,
                    events = MonthGrid.eventsOn(selected, events),
                    categories = categories,
                    onAdd = { quickAddDate = selected },
                    modifier = Modifier.width(340.dp).fillMaxHeight().background(Palette.Panel),
                )
            }
        }
    }

    if (daySheetOpen) {
        ModalBottomSheet(onDismissRequest = { daySheetOpen = false }, containerColor = Palette.Background) {
            DayPanel(
                date = selected,
                events = MonthGrid.eventsOn(selected, events),
                categories = categories,
                onAdd = { daySheetOpen = false; quickAddDate = selected },
                modifier = Modifier.fillMaxWidth().height(420.dp),
            )
        }
    }

    quickAddDate?.let { date ->
        QuickAddSheet(
            initialDate = date,
            categories = categories,
            onAddCategory = vm::addCategory,
            onSave = { vm.addEvent(it); quickAddDate = null },
            onDismiss = { quickAddDate = null },
        )
    }
}

@Composable
private fun MonthHeader(month: YearMonth, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${month.monthValue}월", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Palette.TextStrong)
        if (month.year != YearMonth.now().year) {
            Text("  ${month.year}", fontSize = 16.sp, color = Palette.TextSoft)
        }
        Spacer(Modifier.width(12.dp))
        Surface(shape = RoundedCornerShape(24.dp), shadowElevation = 3.dp, color = Palette.Background) {
            Row {
                IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 달") }
                IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 달") }
            }
        }
    }
}

@Composable
private fun MonthGridView(
    month: YearMonth,
    selected: LocalDate,
    events: List<CalendarEvent>,
    categories: List<CustomCategory>,
    onDayClick: (LocalDate) -> Unit,
) {
    Column(Modifier.padding(horizontal = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            WEEKDAYS.forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, color = Palette.TextSoft, fontSize = 13.sp) }
        }
        MonthGrid.weeks(month).forEach { weekStart ->
            WeekRow(month, weekStart, selected, events, categories, onDayClick)
        }
    }
}

@Composable
private fun WeekRow(
    month: YearMonth,
    weekStart: LocalDate,
    selected: LocalDate,
    events: List<CalendarEvent>,
    categories: List<CustomCategory>,
    onDayClick: (LocalDate) -> Unit,
) {
    val bars = MonthGrid.bars(weekStart, events)
    val rowHeight = DATE_AREA + (BAR_HEIGHT + BAR_GAP) * MAX_LANES + 10.dp
    BoxWithConstraints(Modifier.fillMaxWidth().height(rowHeight)) {
        val colW: Dp = maxWidth / 7
        // Day cells: number, holiday, tap target, hidden-count.
        Row(Modifier.fillMaxSize()) {
            (0..6).forEach { i ->
                val date = weekStart.plusDays(i.toLong())
                val hidden = bars.count { it.lane >= MAX_LANES && i in it.startCol..it.endCol }
                DayCell(date, inMonth = YearMonth.from(date) == month, selected = date == selected, hidden = hidden,
                    modifier = Modifier.weight(1f).fillMaxHeight().clickable { onDayClick(date) })
            }
        }
        bars.filter { it.lane < MAX_LANES }.forEach { bar ->
            val color = argb(CategoryColors.of(bar.item.event.categoryId, categories))
            EventBar(
                label = bar.item.event.barLabel(),
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
private fun DayCell(date: LocalDate, inMonth: Boolean, selected: Boolean, hidden: Int, modifier: Modifier) {
    val holiday = KoreanHolidays.nameOf(date)
    val isToday = date == LocalDate.now()
    val base = when {
        holiday != null || MonthGrid.isSunday(date) -> Palette.Sunday
        MonthGrid.isSaturday(date) -> Palette.Saturday
        else -> Palette.TextStrong
    }
    val numberColor = if (inMonth) base else base.copy(alpha = 0.35f)
    Column(modifier.padding(top = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(
                when {
                    isToday -> Palette.Today
                    selected -> Palette.InputBg
                    else -> Color.Transparent
                },
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text("${date.dayOfMonth}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (isToday) Color.White else numberColor)
        }
        Text(
            holiday ?: if (hidden > 0) "+$hidden" else "",
            fontSize = 8.sp,
            color = if (holiday != null) numberColor else Palette.TextSoft,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
fun EventBar(label: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.18f))) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(color))
        Text(
            label,
            modifier = Modifier.weight(1f).padding(horizontal = 3.dp).align(Alignment.CenterVertically),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = Palette.ChipText,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center,
        )
    }
}

/** Title plus start time, the way the example writes "마프실 18:30". */
fun com.htb.carplan.shared.model.Event.barLabel(): String =
    if (!allDay && startTime != null) "$title $startTime" else title
