package com.htb.carplan.phone.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.htb.carplan.phone.ui.CenterSheet
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.phone.ui.argb
import com.htb.carplan.shared.calendar.CategoryColors
import com.htb.carplan.shared.model.BuiltInCategory
import com.htb.carplan.shared.model.ChecklistItem
import com.htb.carplan.shared.model.CustomCategory
import com.htb.carplan.shared.model.Event
import com.htb.carplan.shared.model.Group
import com.htb.carplan.shared.model.Place
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val REMINDERS = listOf<Int?>(null, 0, 10, 30, 60, 1440)

/** Repeat options kept inside the date chip (출퇴근용). */
private enum class Repeat(val label: String, val rule: (LocalDate) -> String?) {
    NONE("반복 안 함", { null }),
    DAILY("매일", { "DAILY" }),
    WEEKDAYS("평일마다", { "WEEKLY:MO,TU,WE,TH,FR" }),
    WEEKLY("매주 같은 요일", { d -> "WEEKLY:" + d.dayOfWeek.name.take(2) }),
}

/**
 * Q09 빠른 입력: one line plus chips. [장소] comes first because a place is what lets
 * the car take you there; then [날짜] [알림] [메모] [체크리스트] [카테고리 ∨].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddSheet(
    initialDate: LocalDate,
    categories: List<CustomCategory>,
    onAddCategory: (String, Long, (String) -> Unit) -> Unit,
    /** Saves the event; the second value is the group a trip goes to (null = solo / caller decides). */
    onSave: (Event, String?) -> Unit,
    onDismiss: () -> Unit,
    /** 4장: "새 여행" from a group screen fixes the category to 여행 and hides the category and group chips. */
    fixedCategoryId: String? = null,
    /** 4장: groups to pick from for a trip; empty hides the chip. */
    groups: List<Group> = emptyList(),
    defaultGroupId: String? = null,
    onCreateGroup: (String, (String) -> Unit) -> Unit = { _, _ -> },
) {
    var title by remember { mutableStateOf("") }
    var start by remember { mutableStateOf(initialDate) }
    var end by remember { mutableStateOf(initialDate) }
    var time by remember { mutableStateOf<String?>(null) }
    var repeat by remember { mutableStateOf(Repeat.NONE) }
    var reminder by remember { mutableStateOf<Int?>(null) }
    var memo by remember { mutableStateOf<String?>(null) }
    val checklist = remember { mutableStateListOf<String>() }
    var categoryId by remember { mutableStateOf(fixedCategoryId ?: BuiltInCategory.OTHER.id) }
    var place by remember { mutableStateOf<String?>(null) }

    var groupId by remember { mutableStateOf(defaultGroupId) }
    var groupMenu by remember { mutableStateOf(false) }
    var newGroup by remember { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { titleFocus.requestFocus() } // the cursor is in the title the moment the dialog opens
    var dateMenu by remember { mutableStateOf(false) }
    var rangePicker by remember { mutableStateOf(false) }
    var timePicker by remember { mutableStateOf(false) }
    var reminderMenu by remember { mutableStateOf(false) }
    var categorySheet by remember { mutableStateOf(false) }

    val isTrip = categoryId == BuiltInCategory.TRIP.id

    fun save() {
        if (title.isBlank()) return
        if (isTrip) {
            // 3장: a trip is title + dates; its places live in day stops, edited on the next screen.
            onSave(Event(categoryId = categoryId, title = title.trim(), startDate = start.toString(), endDate = end.toString(), allDay = true, memo = memo.orEmpty().trim()), groupId)
            return
        }
        onSave(
            Event(
                categoryId = categoryId,
                title = title.trim(),
                startDate = start.toString(),
                endDate = end.toString(),
                allDay = time == null,
                startTime = time,
                place = place?.takeIf { it.isNotBlank() }?.let { Place(name = it.trim()) },
                repeat = repeat.rule(start),
                reminderMinutes = reminder,
                memo = memo.orEmpty().trim(),
                checklist = checklist.filter { it.isNotBlank() }.map { ChecklistItem(it.trim()) },
            ),
            null,
        )
    }

    CenterSheet(onDismiss = onDismiss) {
        Column(Modifier.imePadding()) {
            Text(if (isTrip) "여행 추가" else "일정 추가", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.padding(bottom = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text(if (isTrip) "어디로 떠나요? (여행 이름)" else "무슨 일정이에요?", fontSize = 26.sp, fontWeight = FontWeight.Bold) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f).focusRequester(titleFocus),
                )
                IconButton(onClick = ::save, enabled = title.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "추가", tint = if (title.isNotBlank()) Palette.Navy else Palette.TextSoft)
                }
            }

            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!isTrip) Chip(Icons.Filled.Place, place?.takeIf { it.isNotBlank() } ?: "장소", active = place != null) { if (place == null) place = "" }
                Box {
                    Chip(Icons.Filled.DateRange, dateLabel(start, end, if (isTrip) null else time, if (isTrip) Repeat.NONE else repeat), active = true) { if (isTrip) rangePicker = true else dateMenu = true }
                    DropdownMenu(dateMenu, onDismissRequest = { dateMenu = false }) {
                        DropdownMenuItem({ Text("날짜 고르기 (여러 날 가능)") }, onClick = { dateMenu = false; rangePicker = true })
                        DropdownMenuItem({ Text(if (time == null) "시간 정하기" else "시간 바꾸기 ($time)") }, onClick = { dateMenu = false; timePicker = true })
                        if (time != null) DropdownMenuItem({ Text("하루 종일로") }, onClick = { dateMenu = false; time = null })
                        HorizontalDivider()
                        Repeat.entries.forEach { r ->
                            DropdownMenuItem({ Text(r.label, fontWeight = if (r == repeat) FontWeight.Bold else FontWeight.Normal) }, onClick = { dateMenu = false; repeat = r })
                        }
                    }
                }
                if (!isTrip) Box {
                    Chip(Icons.Filled.Notifications, reminder?.let(::reminderLabel), active = reminder != null) { reminderMenu = true }
                    DropdownMenu(reminderMenu, onDismissRequest = { reminderMenu = false }) {
                        REMINDERS.forEach { m ->
                            DropdownMenuItem({ Text(m?.let(::reminderLabel) ?: "알림 없음") }, onClick = { reminderMenu = false; reminder = m })
                        }
                    }
                }
                Chip(Icons.Filled.Edit, null, active = memo != null) { memo = if (memo == null) "" else memo }
                if (!isTrip) Chip(Icons.AutoMirrored.Filled.List, null, active = checklist.isNotEmpty()) { checklist.add("") }
                if (fixedCategoryId == null) CategoryChip(CategoryColors.label(categoryId, categories), argb(CategoryColors.of(categoryId, categories))) { categorySheet = true }
                if (isTrip && fixedCategoryId == null && groups.isNotEmpty()) Box {
                    val g = groups.firstOrNull { it.id == groupId }
                    Chip(Icons.Filled.Person, g?.name ?: "그룹", active = g != null && !g.solo) { groupMenu = true }
                    DropdownMenu(groupMenu, onDismissRequest = { groupMenu = false }) {
                        groups.forEach { gr ->
                            DropdownMenuItem({ Text(gr.name + if (gr.solo) " (혼자)" else "  · ${gr.memberIds.size}명", fontWeight = if (gr.id == groupId) FontWeight.Bold else FontWeight.Normal) }, onClick = { groupMenu = false; groupId = gr.id })
                        }
                        HorizontalDivider()
                        DropdownMenuItem({ Text("새 그룹 만들기") }, onClick = { groupMenu = false; newGroup = true })
                    }
                }
            }
            if (isTrip) Text(
                "여행은 날짜만 고르고 추가하면, 바로 날짜별 장소 칸을 짜는 화면으로 가요 (3장)",
                fontSize = 14.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 10.dp),
            )

            memo?.let { m ->
                SmallField(m, { memo = it }, "메모", onRemove = { memo = null })
            }
            place?.let { p ->
                // TODO(3장): Kakao Local keyword search once the Kakao key is issued.
                SmallField(p, { place = it }, "장소 이름 (카카오 검색은 키 받으면 연결)", onRemove = { place = null })
            }
            checklist.forEachIndexed { i, item ->
                SmallField(item, { checklist[i] = it }, "체크리스트 항목", onRemove = { checklist.removeAt(i) })
            }
        }
    }

    if (rangePicker) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = start.toMillis(),
            initialSelectedEndDateMillis = end.toMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { rangePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedStartDateMillis?.let { s ->
                        start = s.toLocalDate()
                        end = (state.selectedEndDateMillis ?: s).toLocalDate()
                    }
                    rangePicker = false
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { rangePicker = false }) { Text("취소") } },
        ) {
            DateRangePicker(state, modifier = Modifier.height(480.dp), title = null, headline = null, showModeToggle = false)
        }
    }

    if (timePicker) {
        val parts = time?.split(":")?.map { it.toInt() }
        val state = rememberTimePickerState(initialHour = parts?.get(0) ?: 9, initialMinute = parts?.get(1) ?: 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { timePicker = false },
            title = { Text("시간") },
            text = { TimeInput(state) },
            confirmButton = { TextButton(onClick = { time = "%02d:%02d".format(state.hour, state.minute); timePicker = false }) { Text("확인") } },
            dismissButton = { TextButton(onClick = { timePicker = false }) { Text("취소") } },
        )
    }

    if (newGroup) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newGroup = false },
            shape = Corner, containerColor = Palette.Background,
            title = { Text("새 그룹") },
            text = { TextField(name, { name = it }, placeholder = { Text("예: 대학 동기, 우리 가족") }, singleLine = true) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { newGroup = false; onCreateGroup(name) { groupId = it } }) { Text("만들기") } },
            dismissButton = { TextButton(onClick = { newGroup = false }) { Text("취소") } },
        )
    }

    if (categorySheet) {
        CategorySheet(
            selectedId = categoryId,
            custom = categories,
            onPick = { categoryId = it; categorySheet = false },
            onAddCategory = onAddCategory,
            onDismiss = { categorySheet = false },
        )
    }
}

@Composable
private fun Chip(icon: ImageVector, label: String?, active: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = Corner, color = Palette.Background, border = BorderStroke(1.dp, if (active) Palette.Navy else Palette.SheetEdge)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (active) Palette.Navy else Palette.TextSoft, modifier = Modifier.size(24.dp))
            label?.let {
                Spacer(Modifier.size(8.dp))
                Text(it, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.Navy)
            }
        }
    }
}

@Composable
private fun CategoryChip(label: String, color: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = Corner, color = Palette.Background, border = BorderStroke(1.dp, Palette.SheetEdge)) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(Corner).background(color))
            Spacer(Modifier.size(8.dp))
            Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.Navy)
            Icon(Icons.Filled.ArrowDropDown, null, tint = Palette.Navy)
        }
    }
}

@Composable
private fun SmallField(value: String, onChange: (String) -> Unit, hint: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value, onChange, placeholder = { Text(hint, fontSize = 17.sp) }, singleLine = true, modifier = Modifier.weight(1f),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 17.sp),
            shape = Corner,
            colors = TextFieldDefaults.colors(focusedContainerColor = Palette.Background, unfocusedContainerColor = Palette.Background, focusedIndicatorColor = Palette.Navy, unfocusedIndicatorColor = Palette.SheetEdge),
        )
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "지우기", tint = Palette.TextSoft) }
    }
}

private fun dateLabel(start: LocalDate, end: LocalDate, time: String?, repeat: Repeat): String {
    val today = LocalDate.now()
    fun one(d: LocalDate) = when (d) {
        today -> "오늘"
        today.plusDays(1) -> "내일"
        else -> "${d.monthValue}/${d.dayOfMonth}"
    }
    val days = if (start == end) one(start) else "${one(start)}–${one(end)}"
    return listOfNotNull(days, time, repeat.takeIf { it != Repeat.NONE }?.label).joinToString(" ")
}

private fun LocalDate.toMillis() = atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
private fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
