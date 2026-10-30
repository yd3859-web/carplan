package com.htb.carplan.phone.record

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.htb.carplan.phone.trip.TripDayScreen
import com.htb.carplan.phone.trip.TripRef
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.shared.data.CalendarEvent
import com.htb.carplan.shared.data.CalendarRepository
import com.htb.carplan.shared.data.FirestorePaths
import com.htb.carplan.shared.data.RecordRepository
import com.htb.carplan.shared.data.Session
import com.htb.carplan.shared.data.SessionRepository
import com.htb.carplan.shared.model.BuiltInCategory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 14장 기록 탭: days that have (or can have) records — trips and "일상 기록" days, newest first. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordTabViewModel : ViewModel() {
    private val sessions = SessionRepository()
    private val calendar = CalendarRepository()
    private val records = RecordRepository()
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session
    val message = MutableStateFlow<String?>(null)

    val days: StateFlow<List<CalendarEvent>> = _session.filterNotNull()
        .flatMapLatest { s -> calendar.observeGroups(s.uid).map { gs -> gs.map { it.id } } }
        .flatMapLatest { ids ->
            val today = LocalDate.now()
            calendar.observeEvents(ids, today.minusDays(365).toString(), today.toString())
        }
        .map { list ->
            list.filter { !it.event.deleted && (it.event.categoryId == BuiltInCategory.TRIP.id || it.event.id.startsWith("log-")) }
                .sortedByDescending { it.event.startDate }
        }
        .catch { message.value = it.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init { viewModelScope.launch { runCatching { sessions.ensureSession() }.onSuccess { _session.value = it } } }

    suspend fun summary(p: DiaryDay) = runCatching { records.daySummary(p.groupId, p.eventId, p.date) }.getOrDefault(emptyList())

    /** "오늘 기록 칸 직접 추가": opens (creating if needed) today's 일상 기록 and hands back its ref. */
    fun openTodayLog(onReady: (TripRef) -> Unit) {
        val s = _session.value ?: return
        viewModelScope.launch {
            val today = LocalDate.now().toString()
            runCatching { records.ensureDailyLog(s.soloGroupId, today, s.uid) }
                .onSuccess { onReady(TripRef(s.soloGroupId, it, today)) }
                .onFailure { message.value = it.message }
        }
    }
}

/** One page of the diary: a trip day or an 일상 기록 day. */
data class DiaryDay(val groupId: String, val eventId: String, val date: String, val title: String, val isLog: Boolean)

@Composable
fun RecordTab(vm: RecordTabViewModel = viewModel()) {
    val days by vm.days.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf<List<String>?>(null) }
    open?.let { (g, e, d) ->
        TripDayScreen(TripRef(g, e, d), onBack = { open = null })
        return
    }
    val today = LocalDate.now()
    // A multi-day trip is one page per day (up to today); newest first.
    val pages = remember(days) {
        days.flatMap { ce ->
            val start = LocalDate.parse(ce.event.startDate)
            val end = minOf(LocalDate.parse(ce.event.endDate.ifBlank { ce.event.startDate }), today)
            generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }
                .map { DiaryDay(ce.groupId, ce.event.id, it.toString(), ce.event.title, ce.event.id.startsWith("log-")) }.toList()
        }.sortedByDescending { it.date }
    }
    val todayPages = pages.filter { it.date == today.toString() }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("기록", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.padding(top = 16.dp))
            Text("날마다 한 장씩 — 차를 세운 곳, 사진, 한 줄", fontSize = 15.sp, color = Palette.TextSoft)
            message?.let { Text(it, color = Palette.Sunday, fontSize = 15.sp) }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            // Today first, as a big page you can always write on.
            item {
                TodayPage(today, todayPages.firstOrNull { !it.isLog } ?: todayPages.firstOrNull(), vm) { ref -> open = ref }
            }
            val past = pages.filter { it.date != today.toString() }
            if (past.isNotEmpty()) item { Text("지난 날", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 24.dp, bottom = 6.dp)) }
            items(past, key = { it.groupId + it.eventId + it.date }) { p ->
                DiaryCard(p, vm) { open = listOf(p.groupId, p.eventId, p.date) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun TodayPage(today: LocalDate, page: DiaryDay?, vm: RecordTabViewModel, onOpen: (List<String>) -> Unit) {
    val recs = page?.let { rememberRecords(it, vm) }.orEmpty()
    Column(Modifier.fillMaxWidth().padding(top = 14.dp).border(1.dp, Palette.SheetEdge, Corner).padding(18.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${today.dayOfMonth}", fontSize = 40.sp, fontWeight = FontWeight.Black, color = Palette.TextStrong)
            Text("  ${today.monthValue}월 · ${today.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.KOREAN)} · 오늘", fontSize = 17.sp, color = Palette.TextSoft, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (recs.isEmpty()) Text("아직 쓴 게 없어요. 차를 세우면 칸이 생기고, 직접 쓸 수도 있어요.", fontSize = 16.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 4.dp))
        else DiaryBody(page!!, recs)
        Button(
            onClick = { if (page != null && !page.isLog) onOpen(listOf(page.groupId, page.eventId, page.date)) else vm.openTodayLog { onOpen(listOf(it.groupId, it.eventId, it.date)) } },
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(52.dp), shape = Corner,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy),
        ) { Text(if (recs.isEmpty()) "오늘 쓰기" else "오늘 이어 쓰기", fontWeight = FontWeight.SemiBold, fontSize = 17.sp) }
    }
}

@Composable
private fun DiaryCard(p: DiaryDay, vm: RecordTabViewModel, onClick: () -> Unit) {
    val recs = rememberRecords(p, vm)
    if (p.isLog && recs != null && recs.isEmpty()) return // an 일상 기록 day nobody wrote in is not a page
    val d = LocalDate.parse(p.date)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp)) {
        Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${d.dayOfMonth}", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
            Text("${d.monthValue}월 ${d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.KOREAN)}", fontSize = 14.sp, color = Palette.TextSoft)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            if (recs == null) Text(p.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
            else DiaryBody(p, recs)
        }
    }
    HorizontalDivider(color = Palette.Line)
}

/** Title, the places in order, the first line someone wrote, and up to 4 photos. */
@Composable
private fun DiaryBody(p: DiaryDay, recs: List<com.htb.carplan.shared.model.Record>) {
    Text(if (p.isLog) "일상" else p.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
    val path = recs.map { it.place.name.ifBlank { "이름 없는 곳" } }.fold(listOf<String>()) { acc, n -> if (acc.lastOrNull() == n) acc else acc + n }
    if (recs.isNotEmpty()) Text(path.joinToString(" → ") + "  ·  ${path.size}곳", fontSize = 15.sp, color = Palette.ChipText, maxLines = 2, modifier = Modifier.padding(top = 2.dp))
    else Text("들른 곳 없음", fontSize = 15.sp, color = Palette.TextSoft)
    recs.firstOrNull { it.note.isNotBlank() }?.let { Text("“${it.note}”", fontSize = 17.sp, color = Palette.TextStrong, modifier = Modifier.padding(top = 6.dp)) }
    val photos = recs.flatMap { it.photoUrls }.take(4)
    if (photos.isNotEmpty()) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        photos.forEach { url -> coil3.compose.AsyncImage(model = url, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.size(72.dp).clip(Corner)) }
    }
}

/** null while loading. */
@Composable
private fun rememberRecords(p: DiaryDay, vm: RecordTabViewModel): List<com.htb.carplan.shared.model.Record>? {
    val state = produceState<List<com.htb.carplan.shared.model.Record>?>(null, p.groupId, p.eventId, p.date) { value = vm.summary(p) }
    return state.value
}
