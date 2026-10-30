package com.htb.carplan.phone.record

import androidx.compose.foundation.clickable
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

@Composable
fun RecordTab(vm: RecordTabViewModel = viewModel()) {
    val days by vm.days.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf<List<String>?>(null) }
    open?.let { (g, e, d) ->
        TripDayScreen(TripRef(g, e, d), onBack = { open = null })
        return
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        Text("기록", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
        Text("차를 세울 때마다 그날 레일에 칸이 쌓여요. 여행이 없는 날은 \"일상 기록\"으로.", fontSize = 14.sp, color = Palette.TextSoft)
        message?.let { Text(it, color = Palette.Sunday, fontSize = 14.sp) }
        Button(
            onClick = { vm.openTodayLog { open = listOf(it.groupId, it.eventId, it.date) } },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(50.dp), shape = Corner,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy),
        ) { Text("오늘 기록 열기", fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
        LazyColumn(Modifier.weight(1f).padding(top = 12.dp)) {
            if (days.isEmpty()) item { Text("아직 기록이 없어요", color = Palette.TextSoft, fontSize = 15.sp, modifier = Modifier.padding(vertical = 12.dp)) }
            items(days, key = { it.groupId + it.event.id }) { d ->
                val e = d.event
                Row(Modifier.fillMaxWidth().clickable { open = listOf(d.groupId, e.id, e.startDate) }.padding(vertical = 12.dp)) {
                    Text(e.startDate, fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(end = 14.dp))
                    Column {
                        Text(e.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
                        Text(if (e.id.startsWith("log-")) "일상 기록" else "여행", fontSize = 13.sp, color = Palette.TextSoft)
                    }
                }
                HorizontalDivider(color = Palette.Line)
            }
        }
    }
}
