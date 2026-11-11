package com.htb.carplan.phone.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.htb.carplan.phone.ui.CenterSheet
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.phone.ui.argb
import com.htb.carplan.shared.calendar.CategoryColors
import com.htb.carplan.shared.data.CalendarEvent
import com.htb.carplan.shared.model.BuiltInCategory
import com.htb.carplan.shared.model.CustomCategory
import com.htb.carplan.shared.model.Place
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 일정 고치기 (팀장 2026-11-04: "캘린더에서 등록했을 때 여행을 수정하는 기능이 없네"). Title, category, dates, time and
 * place of an event already on the calendar. An event that is not a trip gets [여행으로 바꾸기]: it becomes a trip, its
 * place the first stop, and the trip's day opens so more places can be added (경유지 여러 개). [지우기] hides it
 * (soft delete, like everywhere else).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditSheet(
    item: CalendarEvent,
    categories: List<CustomCategory>,
    onAddCategory: (String, Long, (String) -> Unit) -> Unit,
    onSave: (title: String, categoryId: String, start: LocalDate, end: LocalDate, allDay: Boolean, time: String?, place: Place?) -> Unit,
    onMakeTrip: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val e = item.event
    val isTrip = e.categoryId == BuiltInCategory.TRIP.id
    var title by remember { mutableStateOf(e.title) }
    var categoryId by remember { mutableStateOf(e.categoryId) }
    var start by remember { mutableStateOf(LocalDate.parse(e.startDate)) }
    var end by remember { mutableStateOf(LocalDate.parse(e.endDate.ifBlank { e.startDate })) }
    var allDay by remember { mutableStateOf(e.allDay) }
    var time by remember { mutableStateOf(e.startTime) }
    var place by remember { mutableStateOf(e.place) }
    var pickingPlace by remember { mutableStateOf(false) }
    var rangePicker by remember { mutableStateOf(false) }
    var timePicker by remember { mutableStateOf(false) }
    var categorySheet by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val nowTrip = categoryId == BuiltInCategory.TRIP.id

    CenterSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text(if (isTrip) "여행 정보 고치기" else "일정 고치기", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(title, { title = it }, label = { Text("제목") }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp))
            Spacer(Modifier.height(10.dp))
            Line("종류") {
                Row(Modifier.clip(Corner).clickable(enabled = !isTrip) { categorySheet = true }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(4.dp).height(18.dp).background(argb(CategoryColors.of(categoryId, categories))))
                    Text("  " + CategoryColors.label(categoryId, categories), fontSize = 17.sp, color = Palette.TextStrong, fontWeight = FontWeight.SemiBold)
                    if (!isTrip) Text("  바꾸기", fontSize = 15.sp, color = Palette.TextSoft)
                }
            }
            Line("날짜") {
                Text(
                    if (start == end) "${start.monthValue}/${start.dayOfMonth}" else "${start.monthValue}/${start.dayOfMonth} – ${end.monthValue}/${end.dayOfMonth}",
                    fontSize = 17.sp, color = Palette.TextStrong, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(Corner).clickable { rangePicker = true }.padding(vertical = 6.dp),
                )
            }
            if (!nowTrip) Line("시간") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Toggle("종일", allDay) { allDay = true }
                    Toggle(time ?: "시간 정하기", !allDay) { allDay = false; timePicker = true }
                }
            }
            if (!nowTrip) Line("장소") {
                Text(place?.name?.ifBlank { null } ?: "없음 · 고르기", fontSize = 17.sp, color = if (place != null) Palette.TextStrong else Palette.TextSoft, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(Corner).clickable { pickingPlace = true }.padding(vertical = 6.dp))
            }
            HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(vertical = 12.dp))
            // The way to many places (경유지 여러 개): an ordinary event becomes a trip with stops.
            if (!isTrip) {
                OutlinedButton(onClick = onMakeTrip, shape = Corner, border = BorderStroke(1.dp, Palette.Navy), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("여행으로 바꾸기 · 장소 여러 곳 넣기", fontSize = 16.sp, color = Palette.Navy, fontWeight = FontWeight.Bold)
                }
                Text(e.place?.let { "${com.htb.carplan.shared.text.Josa.ga(it.name)} 첫 칸이 되고, 이어서 장소를 더 넣을 수 있어요" }
                    ?: "여행 칸 화면이 열려서 장소를 차례로 넣을 수 있어요", fontSize = 14.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { confirmDelete = true }) { Text("지우기", fontSize = 16.sp, color = Palette.Sunday) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("취소", fontSize = 16.sp, color = Palette.TextSoft) }
                Button(
                    onClick = { onSave(title.ifBlank { e.title }, categoryId, start, end, if (nowTrip) true else allDay, time, place) },
                    shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy),
                ) { Text("저장", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }

    if (pickingPlace) com.htb.carplan.phone.trip.PlaceSearchSheet(
        vm = com.htb.carplan.phone.trip.FreePlaces, initialQuery = place?.name.orEmpty(),
        onPick = { place = it; pickingPlace = false }, onDismiss = { pickingPlace = false },
    )
    if (rangePicker) {
        val state = rememberDateRangePickerState(initialSelectedStartDateMillis = start.millis(), initialSelectedEndDateMillis = end.millis())
        DatePickerDialog(
            onDismissRequest = { rangePicker = false },
            confirmButton = { TextButton(onClick = {
                state.selectedStartDateMillis?.let { s -> start = s.day(); end = (state.selectedEndDateMillis ?: s).day() }
                rangePicker = false
            }) { Text("확인") } },
            dismissButton = { TextButton(onClick = { rangePicker = false }) { Text("취소") } },
        ) { DateRangePicker(state, modifier = Modifier.height(480.dp), title = null, headline = null, showModeToggle = false) }
    }
    if (timePicker) {
        val parts = time?.split(":")?.mapNotNull { it.toIntOrNull() }
        val state = rememberTimePickerState(initialHour = parts?.getOrNull(0) ?: 9, initialMinute = parts?.getOrNull(1) ?: 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { timePicker = false },
            title = { Text("시간") }, text = { TimeInput(state) },
            confirmButton = { TextButton(onClick = { time = "%02d:%02d".format(state.hour, state.minute); timePicker = false }) { Text("확인") } },
            dismissButton = { TextButton(onClick = { timePicker = false }) { Text("취소") } },
        )
    }
    if (categorySheet) CategorySheet(
        selectedId = categoryId, custom = categories,
        onPick = { categoryId = it; categorySheet = false },
        onAddCategory = onAddCategory, onDismiss = { categorySheet = false },
    )
    // Our centre window, not the round system alert (디자인 규칙 4dp, QA 2026-11-04).
    if (confirmDelete) CenterSheet(onDismiss = { confirmDelete = false }) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(if (isTrip) "이 여행을 지울까요?" else "이 일정을 지울까요?", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
            Text("${e.title} · 지운 뒤 잠깐 [되살리기]가 떠요", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { confirmDelete = false }, shape = Corner, border = BorderStroke(1.dp, Palette.Line), modifier = Modifier.weight(1f).height(48.dp)) { Text("취소", color = Palette.TextStrong) }
                Button(onClick = { confirmDelete = false; onDelete() }, shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Sunday), modifier = Modifier.weight(1f).height(48.dp)) { Text("지우기", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun Line(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.width(56.dp))
        content()
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, shape = Corner, border = BorderStroke(1.dp, if (on) Palette.Navy else Palette.SheetEdge)) {
        Text(label, fontSize = 15.sp, color = if (on) Palette.TextStrong else Palette.TextSoft, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
    }
}

private fun LocalDate.millis() = atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
private fun Long.day(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
