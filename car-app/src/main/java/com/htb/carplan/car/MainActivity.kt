package com.htb.carplan.car

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

// Hyundai (Pleos) system look: white ground, near-black text, hairlines, one blue accent, black primary button.
private val Bg = Color(0xFFFFFFFF)
private val Ink = Color(0xFF111111)
private val Soft = Color(0xFF8A9096)
private val Line = Color(0xFFE4E6E9)
private val Accent = Color(0xFF0066B3)
private val Warn = Color(0xFFD9342B)
private val Corner = RoundedCornerShape(4.dp)

class MainActivity : ComponentActivity() {
    private val vm: CarHomeViewModel by viewModels()

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Ink, secondary = Accent, background = Bg, surface = Bg, onSurface = Ink, outline = Line)) {
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
    val trip by vm.trip.collectAsStateWithLifecycle()
    val prompt by vm.prompt.collectAsStateWithLifecycle()
    val guiding by vm.guiding.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        StatusBand(car, vehicle)
        error?.let { Text("오류: $it", color = Warn, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp)) }
        if (car?.ownerUid == null) {
            LinkCodeScreen(car?.linkCode)
            return@Column
        }
        prompt?.let { p -> NextPromptBar(p, onGo = vm::go, onLater = vm::later) }
        val today = LocalDate.now()
        val todayEvents = vm.eventsOn(today, week)
        val next = vm.nextDestination(week)
        val tripNext = trip?.next
        Row(Modifier.fillMaxSize()) {
            TodayList(today, todayEvents, next, trip, Modifier.weight(1.2f).fillMaxHeight())
            VerticalDivider(color = Line)
            WeekList(vm, week, Modifier.weight(0.9f).fillMaxHeight())
            VerticalDivider(color = Line)
            Column(Modifier.weight(0.8f).fillMaxHeight().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    tripNext != null -> BigButton("${tripNext.place.name}로 안내", primary = true) { vm.go() }
                    next != null -> BigButton("${next.event.place?.name}로 안내", primary = true) { vm.goTo(next) }
                    else -> BigButton("다음 목적지 없음", enabled = false, primary = true) { }
                }
                BigButton("다음 목적지 묻기", enabled = tripNext != null && prompt == null) { vm.askNow() }
                BigButton("내비 열기") { vm.openNavigation() }
                if (guiding && tripNext != null) BigButton("${tripNext.place.name} 도착 처리 (시연용)") { vm.debugArrive() }
                Spacer(Modifier.weight(1f))
                BigButton(
                    if (vehicle.drivingRestricted) "주행 중엔 일정 추가 불가" else "일정 추가",
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
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("carplan", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Stat("연료", v.fuelPercent?.let { "${it.toInt()}%" } ?: "—")
        Stat("주행가능", v.rangeKm?.let { "${it.toInt()} km" } ?: "—")
        Stat("기어", v.gear.name)
        if (v.drivingRestricted) Text("주행 중", color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(if (car?.ownerUid != null) "차주 연결됨" else "연결 전", color = Soft, fontSize = 16.sp)
    }
    HorizontalDivider(color = Line)
}

@Composable
private fun Stat(label: String, value: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(label, color = Soft, fontSize = 14.sp, modifier = Modifier.padding(end = 6.dp, bottom = 2.dp))
        Text(value, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LinkCodeScreen(code: String?) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("폰 앱 → 차량 탭에서 이 코드를 입력하세요", color = Soft, fontSize = 24.sp)
        Spacer(Modifier.height(24.dp))
        Text(code?.chunked(3)?.joinToString(" ") ?: "…", color = Ink, fontSize = 96.sp, fontWeight = FontWeight.Black, letterSpacing = 8.sp)
        Spacer(Modifier.height(16.dp))
        Text("이 차의 차주가 일정의 호스트가 됩니다", color = Soft, fontSize = 18.sp)
    }
}

/** 9장: the question as a black band with what the mic heard, and two buttons that always work (Q21). */
@Composable
private fun NextPromptBar(p: NextPrompt, onGo: () -> Unit, onLater: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Ink).padding(horizontal = 28.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("다음 목적지 ${TripNavigator.withRo(p.stop.place.name)} 갈까요?", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text(
                when {
                    p.listening -> "듣는 중 · \"응\" 또는 \"아니\"라고 말해 주세요"
                    p.heard != null -> "들은 말 \"${p.heard}\" · 버튼으로 골라 주세요"
                    else -> "말하거나 버튼을 눌러 주세요"
                },
                color = Color(0xFFB8BDC2), fontSize = 18.sp,
            )
        }
        Button(onClick = onGo, modifier = Modifier.height(80.dp).width(190.dp), shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Ink)) {
            Text("가기", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        Button(onClick = onLater, modifier = Modifier.height(80.dp).width(190.dp).border(1.dp, Color(0xFF55595E), Corner), shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White)) {
            Text("나중에", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SectionTitle(text: String) = Text(text, color = Soft, fontSize = 16.sp, modifier = Modifier.padding(bottom = 10.dp))

@Composable
private fun TodayList(today: LocalDate, events: List<CalendarEvent>, next: CalendarEvent?, trip: TodayTrip?, modifier: Modifier) {
    Column(modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("오늘  ${dayTitle(today)}")
        if (events.isEmpty()) Text("오늘 일정이 없어요", color = Ink, fontSize = 24.sp)
        events.forEachIndexed { idx, item ->
            val e = item.event
            val isNext = item == next
            val isTrip = trip != null && e.id == trip.eventId
            val hot = isNext || isTrip
            if (idx > 0) HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(e.startTime ?: "종일", Modifier.width(88.dp).padding(top = 4.dp), color = if (hot) Accent else Soft, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Column {
                    Text(e.title, color = Ink, fontSize = if (hot) 28.sp else 24.sp, fontWeight = if (hot) FontWeight.Bold else FontWeight.Normal)
                    if (isTrip) TripStops(trip!!) else e.place?.name?.let { Text(it, color = if (isNext) Accent else Soft, fontSize = 19.sp) }
                }
            }
        }
    }
}

/** The day's stops in route order: number, done ones greyed with a check, the next one in the accent color (9장). */
@Composable
private fun TripStops(trip: TodayTrip) {
    val nextId = trip.next?.id
    if (trip.stops.isEmpty()) Text("장소 칸이 아직 없어요 · 폰에서 추가", color = Soft, fontSize = 19.sp)
    trip.stops.forEachIndexed { i, s ->
        val isNext = s.id == nextId
        val color = when { s.visited -> Soft; isNext -> Accent; else -> Ink }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (s.visited) "✓" else "${i + 1}", Modifier.width(34.dp), color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text((s.time?.let { "$it  " } ?: "") + s.place.name, color = color, fontSize = 21.sp, fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal)
            if (isNext) Text("  다음", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (s.memo.isNotBlank()) Text("  · ${s.memo}", color = Soft, fontSize = 17.sp, maxLines = 1)
        }
    }
}

@Composable
private fun WeekList(vm: CarHomeViewModel, week: List<CalendarEvent>, modifier: Modifier) {
    Column(modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("이번 주")
        (0..6L).forEach { i ->
            val d = vm.weekStart.plusDays(i)
            val items = vm.eventsOn(d, week)
            val isToday = d == LocalDate.now()
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                Text(
                    "${d.dayOfMonth} ${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)}",
                    Modifier.width(76.dp),
                    color = if (isToday) Accent else Soft, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(items.joinToString(", ") { it.event.title }.ifEmpty { "—" }, color = if (items.isEmpty()) Soft else Ink, fontSize = 19.sp, maxLines = 2)
            }
        }
    }
}

@Composable
private fun BigButton(label: String, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(84.dp).then(if (primary) Modifier else Modifier.border(1.dp, Line, Corner)),
        shape = Corner,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Ink else Bg,
            contentColor = if (primary) Color.White else Ink,
            disabledContainerColor = if (primary) Color(0xFFD5D8DC) else Bg,
            disabledContentColor = Soft,
        ),
    ) { Text(label, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
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
        shape = Corner,
        containerColor = Bg,
        title = { Text("일정 추가", fontSize = 28.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("무슨 일정이에요?") }, singleLine = true, textStyle = MaterialTheme.typography.headlineSmall, shape = Corner)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("오늘" to 0L, "내일" to 1L, "모레" to 2L).forEach { (label, off) ->
                        FilterChip(selected = dayOffset == off, onClick = { dayOffset = off }, shape = Corner, label = { Text(label, fontSize = 20.sp, modifier = Modifier.padding(8.dp)) })
                    }
                }
                OutlinedTextField(time, { time = it }, label = { Text("시간 (예: 15:00, 비우면 하루 종일)") }, singleLine = true, isError = !timeOk, shape = Corner)
                // Name only until the Kakao key is issued; the navigation searches the name.
                OutlinedTextField(place, { place = it }, label = { Text("장소 (선택)") }, singleLine = true, shape = Corner)
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
            }) { Text("추가", fontSize = 22.sp, color = Ink) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소", fontSize = 22.sp, color = Soft) } },
    )
}

private fun dayTitle(date: LocalDate) =
    "${date.monthValue}월 ${date.dayOfMonth}일 (${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})"
