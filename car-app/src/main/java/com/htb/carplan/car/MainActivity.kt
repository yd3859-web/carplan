package com.htb.carplan.car

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.htb.carplan.shared.data.InviteLinks
import com.htb.carplan.shared.model.Group
import com.htb.carplan.shared.net.GasStation
import com.htb.carplan.shared.model.DartScope
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
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
private val RailPassed = Color(0xFFB9BEC4)

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
    val qrGroup by vm.qrGroup.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val stations by vm.stations.collectAsStateWithLifecycle()
    var debug by remember { mutableStateOf(false) }
    var dartOpen by remember { mutableStateOf(false) }
    val dart by vm.dart.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf(false) }
    var seatedPrompt by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        vm.passengerSeated.collect { if (!vm.vehicle.value.drivingRestricted) { seatedPrompt = true; qr = true } }
    }

    Column(Modifier.fillMaxSize()) {
        val passengers by vm.passengerCount.collectAsStateWithLifecycle()
        val careDue by vm.careDue.collectAsStateWithLifecycle()
        StatusBand(car, vehicle, qrGroup?.name, passengers, careDue)
        error?.let { Text("오류: $it", color = Warn, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp)) }
        notice?.let { Text(it, color = Accent, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp)) }
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
                BigButton("같이 탄 사람 추가", enabled = !vehicle.drivingRestricted) { qr = true }
                if (trip != null) BigButton("다트 (동네 뽑기)", enabled = !vehicle.drivingRestricted) { dartOpen = true }
                Spacer(Modifier.weight(1f))
                BigButton("시연 메뉴") { debug = true }
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
    if (stations.isNotEmpty()) StationDialog(stations, onPick = vm::goViaStation, onDismiss = vm::dismissStations)
    if (debug) DebugDialog(vehicle, vm, onDismiss = { debug = false })
    if (dartOpen && !vehicle.drivingRestricted) {
        AlertDialog(
            onDismissRequest = { dartOpen = false }, shape = Corner, containerColor = Bg,
            title = { Text("다트 — 동네 뽑기", fontSize = 28.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(dart?.resultName ?: "아직 안 던졌어요", fontSize = 36.sp, fontWeight = FontWeight.Black, color = Ink)
                    dart?.let { Text("${it.throwCount}번째 다트! · 폰에도 같이 떠요", fontSize = 18.sp, color = Soft) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { vm.throwDart(DartScope.NATIONAL) }, modifier = Modifier.height(72.dp), shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Ink)) { Text("전국", fontSize = 22.sp) }
                        Button(onClick = { vm.throwDart(DartScope.NEAR) }, modifier = Modifier.height(72.dp), shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Ink)) { Text("내 위치 100km", fontSize = 22.sp) }
                    }
                }
            },
            confirmButton = { if (dart != null) TextButton(onClick = { vm.addDartAsStop(); dartOpen = false }) { Text("오늘 일정에 넣기", fontSize = 22.sp, color = Ink, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { dartOpen = false }) { Text("닫기", fontSize = 22.sp, color = Soft) } },
        )
    }
    if (qr && !vehicle.drivingRestricted) {
        InviteQrDialog(qrGroup, seated = seatedPrompt, onNewGroup = vm::newGroupForQr, onDismiss = { qr = false; seatedPrompt = false })
    }
}

@Composable
private fun StatusBand(car: Car?, v: CarState, groupName: String?, passengers: Int, careDue: String?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("carplan", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Stat("연료", v.fuelPercent?.let { "${it.toInt()}%" } ?: "—")
        Stat("주행가능", v.rangeKm?.let { "${it.toInt()} km" } ?: "—")
        Stat("기어", v.gear.name)
        v.odometerKm?.let { Stat("주행거리", "${"%,d".format(it.toLong())} km") }
        if (v.drivingRestricted) Text("주행 중", color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        groupName?.let { Stat("그룹", it) }
        if (passengers > 0) Stat("동승자", "${passengers}명")
        careDue?.let { Stat("정비", it) }
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
        val change = p.reason == PromptReason.ROUTE_CHANGE
        val fuel = p.reason == PromptReason.FUEL || p.reason == PromptReason.FUELED || p.reason == PromptReason.PICKUP
        val fueled = p.reason == PromptReason.FUELED
        val pickup = p.reason == PromptReason.PICKUP
        val commute = p.reason == PromptReason.COMMUTE
        Column(Modifier.weight(1f)) {
            Text(
                when { fuel || commute -> p.message.orEmpty(); change -> "${p.message}. 경로를 바꿀까요?"; else -> "다음 목적지 ${TripNavigator.withRo(p.stop.place.name)} 갈까요?" },
                color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold,
            )
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
            Text(when { pickup -> "네, 출발"; fueled -> "네"; fuel -> "들를게요"; change -> "바꾸기"; else -> "가기" }, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        Button(onClick = onLater, modifier = Modifier.height(80.dp).width(190.dp).border(1.dp, Color(0xFF55595E), Corner), shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White)) {
            Text(when { pickup -> "공유 없이 출발"; fuel -> "아니요"; change -> "그대로"; else -> "나중에" }, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
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

/**
 * The day's stops on one rail (same idea as the phone): grey check = reached, blue = next, ring = planned.
 * The only motion on the car screen: the rail segment fills down when a stop is reached (Design for Driving).
 */
@Composable
private fun TripStops(trip: TodayTrip) {
    val nextId = trip.next?.id
    if (trip.stops.isEmpty()) Text("장소 칸이 아직 없어요 · 폰에서 추가", color = Soft, fontSize = 19.sp)
    trip.stops.forEachIndexed { i, s ->
        val isNext = s.id == nextId
        val color = when { s.visited -> Soft; isNext -> Accent; else -> Ink }
        val below = trip.stops.getOrNull(i + 1)
        val fill by animateFloatAsState(if (below?.visited == true) 1f else 0f, tween(450), label = "railFill")
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                Modifier.width(44.dp).fillMaxHeight().drawBehind {
                    val x = size.width / 2
                    val cy = 15.dp.toPx()
                    val w = 3.dp.toPx()
                    val dots = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 7.dp.toPx()), 0f)
                    if (i > 0) drawLine(if (s.visited) RailPassed else Line, Offset(x, 0f), Offset(x, cy), w, StrokeCap.Round, if (s.visited) null else dots)
                    if (below != null) {
                        drawLine(Line, Offset(x, cy), Offset(x, size.height), w, StrokeCap.Round, dots)
                        if (fill > 0f) drawLine(RailPassed, Offset(x, cy), Offset(x, cy + (size.height - cy) * fill), w, StrokeCap.Round)
                    }
                },
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(Modifier.padding(top = 4.dp).size(22.dp), contentAlignment = Alignment.Center) {
                    when {
                        s.visited -> Box(Modifier.size(18.dp).clip(CircleShape).background(RailPassed), contentAlignment = Alignment.Center) { Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        isNext -> Box(Modifier.size(22.dp).clip(CircleShape).background(Accent))
                        else -> Box(Modifier.size(18.dp).clip(CircleShape).background(Bg).border(2.dp, Color(0xFFAEB4BA), CircleShape))
                    }
                }
            }
            Row(Modifier.weight(1f).padding(bottom = if (below != null) 10.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                Text((s.time?.let { "$it  " } ?: "") + s.place.name, color = color, fontSize = 21.sp, fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal)
                if (isNext) Text("  다음", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                if (s.memo.isNotBlank()) Text("  · ${s.memo}", color = Soft, fontSize = 17.sp, maxLines = 1)
            }
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

/**
 * 7장 3: the invite QR for today's group. Passengers scan it with the phone app (or any camera:
 * the link opens the Hosting page, which opens the app or explains how to install it).
 * No trip today → [새 그룹] makes one on the spot (Q16).
 */
@Composable
private fun InviteQrDialog(group: Group?, seated: Boolean, onNewGroup: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = Corner,
        containerColor = Bg,
        title = { Text(if (seated) "같이 타셨나요? QR 찍고 들어오세요" else if (group == null) "같이 탄 사람 추가" else "${group.name}에 들어오기", fontSize = 28.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (group == null) {
                    Text("오늘 걸린 여행이 없어요. 새 그룹을 만들면 QR이 떠요.", fontSize = 20.sp, color = Soft)
                } else {
                    val link = InviteLinks.url(group.inviteCode)
                    val bitmap = remember(link) { qrBitmap(link, 560) }
                    Image(bitmap.asImageBitmap(), contentDescription = "초대 QR", modifier = Modifier.size(280.dp))
                    Text("폰으로 QR을 찍으면 바로 그룹에 들어와요", fontSize = 20.sp, color = Ink)
                    Text("코드  ${group.inviteCode}   ·   ${group.memberIds.size}명", fontSize = 20.sp, color = Soft)
                }
            }
        },
        confirmButton = {
            if (group == null) TextButton(onClick = onNewGroup) { Text("새 그룹", fontSize = 22.sp, color = Ink, fontWeight = FontWeight.Bold) }
            else TextButton(onClick = onDismiss) { Text("닫기", fontSize = 22.sp, color = Ink) }
        },
        dismissButton = { if (group == null) TextButton(onClick = onDismiss) { Text("닫기", fontSize = 22.sp, color = Soft) } },
    )
}

/** 12장: the three cheapest stations near the car; one tap makes it the next waypoint. */
@Composable
private fun StationDialog(stations: List<GasStation>, onPick: (GasStation) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, shape = Corner, containerColor = Bg,
        title = { Text("가까운 최저가 주유소", fontSize = 28.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                stations.forEach { st ->
                    Button(onClick = { onPick(st) }, modifier = Modifier.fillMaxWidth().height(76.dp).border(1.dp, Line, Corner), shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Bg, contentColor = Ink)) {
                        Text(st.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("${"%,d".format(st.price)}원", fontSize = 22.sp, color = Accent, fontWeight = FontWeight.Bold)
                        Text("  ${if (st.meters >= 1000) "%.1fkm".format(st.meters / 1000.0) else "${st.meters}m"}", fontSize = 18.sp, color = Soft)
                    }
                }
                Text("고르면 다음 경유지로 들어가고, 그다음 원래 일정으로 이어져요", fontSize = 16.sp, color = Soft)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기", fontSize = 22.sp, color = Soft) } },
    )
}

/** 20장: demo controls — fuel %, car type, fake drive, arrival. */
@Composable
private fun DebugDialog(v: CarState, vm: CarHomeViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, shape = Corner, containerColor = Bg,
        title = { Text("시연 메뉴", fontSize = 28.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("연료/배터리  지금 ${v.fuelPercent?.toInt() ?: "—"}%  ·  ${if (v.isEv == true) "전기차" else if (v.isEv == false) "연료차" else "종류 모름"}", fontSize = 18.sp, color = Soft)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10.0, 20.0, 50.0, 100.0).forEach { p -> FilterChip(selected = v.fuelPercent == p, onClick = { vm.setFuelOverride(p) }, shape = Corner, label = { Text("${p.toInt()}%", fontSize = 18.sp, modifier = Modifier.padding(6.dp)) }) }
                    FilterChip(selected = false, onClick = { vm.setFuelOverride(null) }, shape = Corner, label = { Text("실제값", fontSize = 18.sp, modifier = Modifier.padding(6.dp)) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = v.isEv == false, onClick = { vm.setEvOverride(false) }, shape = Corner, label = { Text("연료차로", fontSize = 18.sp, modifier = Modifier.padding(6.dp)) })
                    FilterChip(selected = v.isEv == true, onClick = { vm.setEvOverride(true) }, shape = Corner, label = { Text("전기차로", fontSize = 18.sp, modifier = Modifier.padding(6.dp)) })
                    FilterChip(selected = false, onClick = { vm.setEvOverride(null) }, shape = Corner, label = { Text("실제값", fontSize = 18.sp, modifier = Modifier.padding(6.dp)) })
                }
                HorizontalDivider(color = Line)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.playFakeDrive(); onDismiss() }, shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Ink)) { Text("가짜 주행 재생 (6초마다 다음 칸 도착)", fontSize = 18.sp) }
                    TextButton(onClick = { vm.stopFakeDrive() }) { Text("멈춤", fontSize = 18.sp, color = Soft) }
                }
                Text("연료 10~20%로 두면 주유 질문이 나와요. 전기차면 충전소 문구.", fontSize = 16.sp, color = Soft)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기", fontSize = 22.sp, color = Ink) } },
    )
}

/** zxing core → black-on-white bitmap. */
private fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return bmp
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
