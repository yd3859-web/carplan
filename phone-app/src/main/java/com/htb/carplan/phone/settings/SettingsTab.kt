package com.htb.carplan.phone.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import com.htb.carplan.phone.calendar.GoogleCalendar
import com.htb.carplan.phone.calendar.MonthGrid
import java.time.YearMonth
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.htb.carplan.phone.BuildConfig
import com.htb.carplan.phone.push.Reminders
import com.htb.carplan.phone.ui.CenterSheet
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.shared.data.CalendarEvent
import com.htb.carplan.shared.data.CalendarRepository
import com.htb.carplan.shared.data.CarRepository
import com.htb.carplan.shared.data.Session
import com.htb.carplan.shared.data.SessionRepository
import com.htb.carplan.shared.model.Car
import com.htb.carplan.shared.model.User
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel : ViewModel() {
    private val sessions = SessionRepository()
    private val calendar = CalendarRepository()
    private val cars = CarRepository()
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session
    val message = MutableStateFlow<String?>(null)

    val me: StateFlow<User?> = _session.filterNotNull().flatMapLatest { calendar.observeUser(it.uid) }
        .catch { message.value = it.message }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val car: StateFlow<Car?> = me.flatMapLatest { u -> u?.carId?.let(calendar::observeCar) ?: flowOf(null) }
        .catch { message.value = it.message }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    /** The next 14 days of my events — what the reminder toggle has to (re)schedule. */
    val upcoming: StateFlow<List<CalendarEvent>> = _session.filterNotNull()
        .flatMapLatest { s -> calendar.observeGroups(s.uid) }
        .flatMapLatest { gs -> calendar.observeEvents(gs.map { it.id }, LocalDate.now().toString(), LocalDate.now().plusDays(14).toString()) }
        .catch { emit(emptyList()) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init { viewModelScope.launch { runCatching { sessions.ensureSession() }.onSuccess { _session.value = it }.onFailure { message.value = it.message } } }

    fun rename(name: String) = viewModelScope.launch {
        val s = _session.value ?: return@launch
        runCatching { sessions.updateName(s.uid, name) }.onFailure { message.value = it.message }
    }
    fun setFuelAlert(percent: Int) = viewModelScope.launch {
        val c = car.value ?: return@launch
        runCatching { cars.setFuelAlert(c.id, percent) }.onFailure { message.value = it.message }
    }
}

/** 설정 탭 (2장 하단 바): 이름 · 친구 코드 · 알림 · 차 · 계정 · 앱. Sections split by hairlines, no cards. */
@Composable
fun SettingsTab(vm: SettingsViewModel = viewModel()) {
    val me by vm.me.collectAsStateWithLifecycle()
    val car by vm.car.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val upcoming by vm.upcoming.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var renaming by remember { mutableStateOf(false) }
    var reminders by remember { mutableStateOf(Reminders.enabled(context)) }
    var googleCal by remember { mutableStateOf(GoogleCalendar.enabled(context)) }
    val googleStatus by GoogleCalendar.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val grid = MonthGrid.of(YearMonth.now())
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (GoogleCalendar.finishAuthorization(context, r.data)) { googleCal = true; GoogleCalendar.setEnabled(context, true); scope.launch { GoogleCalendar.refresh(context, grid.start, grid.endInclusive) } }
        else { googleCal = false; GoogleCalendar.setEnabled(context, false) }
    }
    fun turnOnGoogle() = scope.launch {
        GoogleCalendar.setEnabled(context, true)
        runCatching { GoogleCalendar.authorize(context) }
            .onSuccess { googleCal = true; GoogleCalendar.refresh(context, grid.start, grid.endInclusive) }
            .onFailure { e ->
                if (e is GoogleCalendar.ConsentNeeded) consent.launch(IntentSenderRequest.Builder(e.pendingIntent.intentSender).build())
                else { googleCal = false; GoogleCalendar.setEnabled(context, false); vm.message.value = "구글 캘린더 연결 실패: ${e.message?.take(80)}" }
            }
    }

    val scroll = rememberScrollState()
    com.htb.carplan.phone.ui.OnTabTapAgain { scroll.animateScrollTo(0) }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(scroll).padding(horizontal = 20.dp)) {
        Text("설정", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.padding(top = 18.dp, bottom = 12.dp))
        message?.let { Text(it, color = Palette.Sunday, fontSize = 15.sp) }

        Section("내 정보")
        SettingRow("이름", me?.name ?: "…", action = "바꾸기") { renaming = true }
        SettingRow("내 친구 코드", me?.friendCode ?: "…", hint = "친구가 [같이 탈 사람] 탭에서 이 코드로 나를 추가해요")

        Section("알림")
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("일정 알림", fontSize = 17.sp, color = Palette.TextStrong)
                Text("일정에 [알림]을 넣으면 그 시간에 폰이 알려 줘요 (2주 안의 일정)", fontSize = 15.sp, color = Palette.TextSoft)
            }
            Switch(reminders, onCheckedChange = { on -> reminders = on; Reminders.setEnabled(context, on, upcoming) },
                colors = SwitchDefaults.colors(checkedTrackColor = Palette.Navy, checkedThumbColor = Palette.Background))
        }
        Text("같이 가는 사람 알림(칸 뺌·투표·도착·픽업)은 서버에서 보내요. 차가 가는 중엔 동승자 폰에 \"차가 가는 중\" 알림이 떠 있어요. 폰의 알림 권한이 꺼져 있으면 시스템 설정에서 켜 주세요.", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(bottom = 8.dp))

        Section("캘린더")
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("구글 캘린더 같이 보기", fontSize = 17.sp, color = Palette.TextStrong)
                Text("구글 일정이 \"구글\" 카테고리로 같이 보여요. 읽기만 하고, 우리 일정을 구글로 보내진 않아요.", fontSize = 15.sp, color = Palette.TextSoft)
                googleStatus?.let { Text(it, fontSize = 15.sp, color = Palette.Sunday) }
            }
            Switch(googleCal, onCheckedChange = { on -> if (on) turnOnGoogle() else { googleCal = false; GoogleCalendar.setEnabled(context, false) } },
                colors = SwitchDefaults.colors(checkedTrackColor = Palette.Navy, checkedThumbColor = Palette.Background))
        }

        Section("차")
        val c = car
        var linking by remember { mutableStateOf(false) }
        if (linking) com.htb.carplan.phone.ui.CarLinkSheet(onDismiss = { linking = false })
        if (c == null) SettingRow("연결된 차", "없음", hint = "내 차라면 차 화면에 뜬 6자리를 넣어요", action = "연결") { linking = true }
        else {
            SettingRow("연결된 차", if (c.ownerUid == session?.uid) "내 차 (차주)" else "다른 사람 차")
            Text("주유·충전 알림 기준", fontSize = 17.sp, color = Palette.TextStrong, modifier = Modifier.padding(top = 8.dp))
            Text("연료(배터리)가 이 아래이거나 오늘 일정에 모자라면 차와 폰의 하루 일정에 \"주유하기\" 칸이 생겨요. 모자라는지는 남은 길 + 30km로 계산해요.", fontSize = 15.sp, color = Palette.TextSoft)
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 15, 20, 30).forEach { p ->
                    val on = c.fuelAlertPercent == p
                    Text(
                        "$p%", fontSize = 16.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) Palette.Background else Palette.TextStrong,
                        modifier = Modifier.border(1.dp, if (on) Palette.Navy else Palette.Line, Corner)
                            .then(if (on) Modifier.background(Palette.Navy, Corner) else Modifier)
                            .clickable(enabled = c.ownerUid == session?.uid) { vm.setFuelAlert(p) }.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }

        Section("계정")
        val provider = me?.provider
        if (provider == null) SettingRow("로그인", "아직 안 함 · 익명 ${session?.uid?.takeLast(4) ?: "…"}", hint = "구글로 연결하면 지금 데이터가 그대로 이어지고, 폰을 바꿔도 돼요.", action = "연결") { com.htb.carplan.phone.login.LoginGateBus.reopen.value = true }
        else SettingRow("로그인", when (provider) { "google" -> "구글 계정"; else -> provider } + (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email?.let { " · $it" } ?: ""))

        Section("앱")
        SettingRow("버전", "carplan ${BuildConfig.VERSION_NAME}")
        Text("캘린더에 쓴 일정이 차를 움직인다 · Pleos Connect 공모전", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 4.dp, bottom = 32.dp))
    }

    if (renaming) RenameSheet(me?.name.orEmpty(), onDone = { vm.rename(it); renaming = false }, onDismiss = { renaming = false })
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(top = 10.dp))
    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextSoft, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun SettingRow(label: String, value: String, hint: String? = null, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, color = Palette.TextSoft)
            Text(value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextStrong)
            hint?.let { Text(it, fontSize = 15.sp, color = Palette.TextSoft) }
        }
        action?.let { TextButton(onClick = onAction) { Text(it, color = Palette.TextStrong) } }
    }
}

@Composable
private fun RenameSheet(current: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    CenterSheet(onDismiss = onDismiss) {
        Column {
            Text("이름 바꾸기", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
            Text("같이 가는 사람과 차 화면에 이렇게 보여요", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(bottom = 10.dp))
            OutlinedTextField(name, { name = it.take(20) }, singleLine = true, shape = Corner, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("취소", color = Palette.TextSoft) }
                Button(onClick = { onDone(name) }, enabled = name.isNotBlank(), shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy)) { Text("저장") }
            }
        }
    }
}
