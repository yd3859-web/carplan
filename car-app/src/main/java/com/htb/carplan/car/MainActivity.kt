package com.htb.carplan.car

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htb.carplan.shared.data.CalendarEvent
import com.htb.carplan.shared.model.Car
import com.htb.carplan.shared.model.CarState
import com.htb.carplan.shared.model.Event
import com.htb.carplan.shared.model.Place
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private val Bg = Color(0xFF0F1626)
private val Card = Color(0xFF1A2438)
private val Teal = Color(0xFF1FB5A5)
private val Soft = Color(0xFF9AA4B8)

class MainActivity : ComponentActivity() {
    private val vm: CarHomeViewModel by viewModels()

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Teal, background = Bg, surface = Bg)) {
                Surface(Modifier.fillMaxSize(), color = Bg) { CarHome(vm) }
            }
        }
    }
}

@Composable
private fun CarHome(vm: CarHomeViewModel) {
    val car by vm.car.collectAsStateWithLifecycle()
    val vehicle by vm.vehicle.collectAsStateWithLifecycle()
    val week by vm.week.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        StatusBand(car, vehicle)
        error?.let { Text("오류: $it", color = Color(0xFFFF8A80), fontSize = 16.sp) }
        Spacer(Modifier.height(16.dp))
        if (car?.ownerUid == null) {
            LinkCodeScreen(car?.linkCode)
            return@Column
        }
        val today = LocalDate.now()
        val todayEvents = vm.eventsOn(today, week)
        val next = vm.nextDestination(week)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            TodayList(today, todayEvents, next, Modifier.weight(1.2f).fillMaxHeight())
            WeekList(vm, week, Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.weight(0.8f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                BigButton(
                    if (next != null) "▶  ${next.event.place?.name}로 안내" else "▶  다음 목적지 없음",
                    enabled = next != null, primary = true,
                ) { next?.let(vm::goTo) }
                BigButton("🎤  말하기", enabled = false) { } // 9장 "다음 목적지로 갈까요?"
                BigButton("🧭  내비 열기") { vm.openNavigation() }
                BigButton(
                    if (vehicle.drivingRestricted) "주행 중엔 일정 추가 불가" else "+  일정 추가",
                    enabled = !vehicle.drivingRestricted,
                ) { adding = true }
            }
        }
    }

    if (adding && !vehicle.drivingRestricted) {
        CarAddDialog(onSave = { vm.addEvent(it); adding = false }, onDismiss = { adding = false })
    }
}

@Composable
private fun StatusBand(car: Car?, v: CarState) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("carplan", color = Teal, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text("⛽ " + (v.fuelPercent?.let { "${it.toInt()}%" } ?: "—"), color = Color.White, fontSize = 20.sp)
        Text("🛣 " + (v.rangeKm?.let { "${it.toInt()}km" } ?: "—"), color = Color.White, fontSize = 20.sp)
        Text("기어 ${v.gear.name}", color = Color.White, fontSize = 20.sp)
        if (v.drivingRestricted) Text("주행 중", color = Color(0xFFFFC857), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(if (car?.ownerUid != null) "차주 연결됨" else "연결 전", color = Soft, fontSize = 18.sp)
    }
}

@Composable
private fun LinkCodeScreen(code: String?) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("폰 앱 → 차량 탭에서 이 코드를 입력하세요", color = Soft, fontSize = 26.sp)
        Spacer(Modifier.height(24.dp))
        Text(code?.chunked(3)?.joinToString(" ") ?: "…", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Black, letterSpacing = 8.sp)
        Spacer(Modifier.height(16.dp))
        Text("이 차의 차주가 일정의 호스트가 됩니다", color = Soft, fontSize = 20.sp)
    }
}

@Composable
private fun TodayList(today: LocalDate, events: List<CalendarEvent>, next: CalendarEvent?, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(Card).padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("오늘  ${dayTitle(today)}", color = Soft, fontSize = 20.sp)
        Spacer(Modifier.height(12.dp))
        if (events.isEmpty()) Text("오늘 일정이 없어요", color = Color.White, fontSize = 26.sp)
        events.forEach { item ->
            val e = item.event
            val isNext = item == next
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(e.startTime ?: "종일", Modifier.width(96.dp), color = if (isNext) Teal else Soft, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Column {
                    Text(e.title, color = Color.White, fontSize = if (isNext) 30.sp else 26.sp, fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal)
                    e.place?.name?.let { Text("📍 $it", color = if (isNext) Teal else Soft, fontSize = 20.sp) }
                }
            }
        }
    }
}

@Composable
private fun WeekList(vm: CarHomeViewModel, week: List<CalendarEvent>, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(Card).padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("이번 주", color = Soft, fontSize = 20.sp)
        (0..6L).forEach { i ->
            val d = vm.weekStart.plusDays(i)
            val items = vm.eventsOn(d, week)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    "${d.dayOfMonth} ${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)}",
                    Modifier.width(80.dp),
                    color = if (d == LocalDate.now()) Teal else Soft, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                )
                Text(items.joinToString(", ") { it.event.title }.ifEmpty { "—" }, color = Color.White, fontSize = 20.sp, maxLines = 2)
            }
        }
    }
}

@Composable
private fun BigButton(label: String, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(96.dp),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (primary) Teal else Card, contentColor = Color.White),
    ) { Text(label, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}

/** 차에서 일정 만들기 (Q19): big, few fields, preset days instead of a date picker. */
@Composable
private fun CarAddDialog(onSave: (Event) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var dayOffset by remember { mutableStateOf(0L) }
    var time by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("") }
    val timeOk = time.isEmpty() || Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(time)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("일정 추가", fontSize = 28.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("무슨 일정이에요?") }, singleLine = true, textStyle = MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("오늘" to 0L, "내일" to 1L, "모레" to 2L).forEach { (label, off) ->
                        FilterChip(selected = dayOffset == off, onClick = { dayOffset = off }, label = { Text(label, fontSize = 20.sp, modifier = Modifier.padding(8.dp)) })
                    }
                }
                OutlinedTextField(time, { time = it }, label = { Text("시간 (예: 15:00, 비우면 하루 종일)") }, singleLine = true, isError = !timeOk)
                // TODO(3장): Kakao place search; name only until the key is issued.
                OutlinedTextField(place, { place = it }, label = { Text("장소 (선택)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() && timeOk, onClick = {
                val date = LocalDate.now().plusDays(dayOffset).toString()
                onSave(
                    Event(
                        title = title.trim(), startDate = date, endDate = date,
                        allDay = time.isEmpty(), startTime = time.ifEmpty { null },
                        place = place.trim().takeIf { it.isNotEmpty() }?.let { Place(name = it) },
                    ),
                )
            }) { Text("추가", fontSize = 22.sp) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소", fontSize = 22.sp) } },
    )
}

private fun dayTitle(date: LocalDate) =
    "${date.monthValue}월 ${date.dayOfMonth}일 (${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})"
