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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
// Dialogs: grey sheet + edge + shadow so they stand off the white screen (정원 2026-10-30).
private val Sheet = Color(0xFFF0F2F5)
private val SheetEdge = Color(0xFFC9CED4)

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
    /** Someone sat down while a question was on screen: the QR waits until it is answered. */
    var seatedWaiting by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        vm.passengerSeated.collect {
            if (vm.vehicle.value.drivingRestricted) return@collect
            seatedPrompt = true
            if (vm.prompt.value == null) qr = true else seatedWaiting = true
        }
    }
    LaunchedEffect(prompt == null, seatedWaiting) { if (prompt == null && seatedWaiting) { seatedWaiting = false; qr = true } }
    // Moving off closes the boarding QR: nobody scans while the car drives.
    LaunchedEffect(vehicle.gear) { if (vehicle.gear == com.htb.carplan.shared.model.Gear.D && seatedPrompt) { qr = false; seatedPrompt = false; seatedWaiting = false } }
    // A notice ("기록 칸: 판교역") is news, not state: it goes after a few seconds.
    LaunchedEffect(notice) { if (notice != null) { kotlinx.coroutines.delay(8_000); vm.clearNotice() } }

    Column(Modifier.fillMaxSize()) {
        val passengers by vm.passengerCount.collectAsStateWithLifecycle()
        val careDue by vm.careDue.collectAsStateWithLifecycle()
        StatusBand(car, vehicle, qrGroup?.name, passengers, careDue, onNavi = vm::openNavigation)
        val sim by vm.sim.collectAsStateWithLifecycle()
        if (sim.running) SimBar(sim, onPause = vm::pauseSim, onSpeed = vm::speedSim, onStop = vm::stopSim)
        error?.let { Text("오류: $it", color = Warn, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp)) }
        notice?.let { Text(it, color = Accent, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp)) }
        if (car?.ownerUid == null) {
            LinkCodeScreen(car?.linkCode)
            return@Column
        }
        val today = LocalDate.now()
        val todayEvents = vm.eventsOn(today, week)
        val next = vm.nextDestination(week)
        val tripNext = trip?.next
        val live by vm.live.collectAsStateWithLifecycle()
        val restricted = vehicle.drivingRestricted
        val forceSplit by vm.previewSplit.collectAsStateWithLifecycle()
        val nextComment by vm.nextComment.collectAsStateWithLifecycle()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Two shapes (정원 2026-10-30): alone → wide; next to the navigation (half the app area) → the day's timeline, big.
            val wide = maxWidth >= 1100.dp
            val compact = !wide || forceSplit
            Row(Modifier.fillMaxSize()) {
                if (forceSplit && wide) Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFE9ECEF)), contentAlignment = Alignment.Center) {
                    Text("내비 자리\n(반반 미리보기)", color = Soft, fontSize = 22.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val promptSlot: (@Composable () -> Unit)? = prompt?.let { p -> { PromptPanel(p, compact = compact, onGo = vm::go, onLater = vm::later) } }
                    if (compact) SplitDayView(trip, next, live, guiding, vehicle.drivingRestricted, promptSlot, onGo = { if (tripNext != null) vm.go() else next?.let(vm::goTo) }, onMore = { more = true })
                    else HomeView(
                        today = today, week = week, vm = vm, trip = trip, next = next, guiding = guiding, live = live, prompt = promptSlot, restricted = restricted, comment = nextComment,
                        onGo = { if (tripNext != null) vm.go() else next?.let(vm::goTo) },
                        onNavi = { vm.openNavigation() },
                        onMore = { more = true },
                    )
                }
            }
        // Pleos 주행 제한: buttons are disabled while moving (view only); the voice question's two buttons stay usable.
        }
    }

    if (more && !vehicle.drivingRestricted) MoreDialog(
        guidingNext = if (guiding) trip?.next?.place?.name else null,
        hasTrip = trip != null,
        onPick = { what ->
            more = false
            when (what) {
                "qr" -> qr = true; "dart" -> dartOpen = true; "add" -> adding = true; "navi" -> vm.openNavigation()
                "ask" -> vm.askNow(); "debug" -> debug = true; "arrive" -> vm.debugArrive()
            }
        },
        onDismiss = { more = false },
    )
    if (adding && !vehicle.drivingRestricted) {
        CarAddDialog(onSave = { vm.addEvent(it); adding = false }, onDismiss = { adding = false })
    }
    if (stations.isNotEmpty()) StationDialog(stations, onPick = vm::goViaStation, onDismiss = vm::dismissStations)
    if (debug) DebugDialog(vehicle, vm, onDismiss = { debug = false })
    if (dartOpen && !vehicle.drivingRestricted) CarDartGame(vm, onDismiss = { dartOpen = false })
    if (qr && !vehicle.drivingRestricted) {
        InviteQrDialog(qrGroup, seated = seatedPrompt, hasTrip = trip != null, onNewGroup = vm::newGroupForQr, onDismiss = { qr = false; seatedPrompt = false })
    }
}

/** All car dialogs: dark scrim, grey sheet with an edge and shadow, white cards inside — readable over the white screen. */
@Composable
private fun CarDialog(onDismiss: () -> Unit, title: String, width: Dp = 780.dp, buttons: @Composable RowScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color(0x8C000000)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = width).fillMaxWidth(0.72f).shadow(24.dp, Corner).clip(Corner).background(Sheet).border(1.dp, SheetEdge, Corner)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { }
                    .padding(28.dp),
            ) {
                Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(16.dp))
                content()
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { buttons() }
            }
        }
    }
}

@Composable
private fun chipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors(
    containerColor = Bg, labelColor = Ink, selectedContainerColor = Ink, selectedLabelColor = Color.White,
)

@Composable
private fun StatusBand(car: Car?, v: CarState, groupName: String?, passengers: Int, careDue: String?, onNavi: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("carplan", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Stat(if (v.isEv == true) "배터리" else "연료", v.fuelPercent?.let { "${it.toInt()}%" } ?: "—")
        v.rangeKm?.takeIf { it > 0 }?.let { Stat("주행가능", "${it.toInt()} km") }
        careDue?.let { Stat("정비", it) }
        Spacer(Modifier.weight(1f))
        groupName?.let { Stat("그룹", it) }
        if (passengers > 0) Stat("동승자", "${passengers}명")
        // Driving: the screen is view-only (바꿀 건 동승자 폰에서); the one thing left to press is the navigation.
        if (v.drivingRestricted) {
            Text("주행 중 · 보기만", color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onNavi) { Text("내비 열기", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
    }
    HorizontalDivider(color = Line)
}

/**
 * 차 첫 화면 (정원 2026-10-30: "표현에 집중, 누르는 건 간결하게"). Left: today's rail, big. Right: the one
 * thing to do next — where, when, one huge button. Everything else lives behind [더보기] (parked only).
 */
@Composable
private fun HomeView(today: LocalDate, week: List<CalendarEvent>, vm: CarHomeViewModel, trip: TodayTrip?, next: CalendarEvent?, guiding: Boolean, live: NaviLive, prompt: (@Composable () -> Unit)?, restricted: Boolean, comment: Pair<String, String>?, onGo: () -> Unit, onNavi: () -> Unit, onMore: () -> Unit) {
    val todayEvents = vm.eventsOn(today, week)
    val others = todayEvents.filter { it.event.id != trip?.eventId }
    val nextName = trip?.next?.place?.name ?: next?.event?.place?.name
    val nextTime = trip?.next?.time ?: next?.event?.startTime
    val later = (1..6L).asSequence().flatMap { d -> vm.eventsOn(today.plusDays(d), week).map { today.plusDays(d) to it } }.firstOrNull()
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1.45f).fillMaxHeight().padding(horizontal = 40.dp, vertical = 28.dp).verticalScroll(rememberScrollState())) {
            Text("오늘  ${dayTitle(today)}", color = Soft, fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))
            if (trip != null) {
                Text(trip.title, color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(18.dp))
                TripStops(trip, big = true)
            } else if (todayEvents.isEmpty()) {
                Text("오늘은 일정이 없어요", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("폰 캘린더에 일정을 넣으면 여기로 와요", color = Soft, fontSize = 20.sp, modifier = Modifier.padding(top = 8.dp))
            }
            if (others.isNotEmpty()) {
                if (trip != null) HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 22.dp))
                others.forEach { item ->
                    val e = item.event
                    val care = e.categoryId == com.htb.carplan.shared.model.BuiltInCategory.CAR.id
                    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(e.startTime ?: "종일", color = Soft, fontSize = 22.sp, modifier = Modifier.width(96.dp))
                        Text(if (care) "차량 관리 · ${e.title}" else e.title, color = if (care) Soft else Ink, fontSize = 26.sp)
                        e.place?.name?.takeIf { !care }?.let { Text("  $it", color = Soft, fontSize = 22.sp) }
                    }
                }
            }
        }
        VerticalDivider(color = Line)
        Column(Modifier.weight(1f).fillMaxHeight().padding(32.dp)) {
            if (prompt != null) {
                prompt()
                // A question while driving (주차장, 경로 바꾸기) must not hide where we are going and how far.
                if (guiding && nextName != null) {
                    Text("가는 중", color = Soft, fontSize = 18.sp, modifier = Modifier.padding(top = 24.dp))
                    Text(nextName, color = Accent, fontSize = 30.sp, fontWeight = FontWeight.Black, maxLines = 1)
                    remainText(live, trip, guiding)?.let { Text(it, color = Ink, fontSize = 22.sp, modifier = Modifier.padding(top = 4.dp)) }
                    LegProgress(live, nextName)
                }
                // The question takes the panel, but the menu must stay reachable while parked.
                Spacer(Modifier.weight(1f))
                if (!restricted) Button(
                    onClick = onMore, modifier = Modifier.fillMaxWidth().height(76.dp).border(1.dp, Line, Corner), shape = Corner,
                    colors = ButtonDefaults.buttonColors(containerColor = Bg, contentColor = Ink),
                ) { Text("더보기", fontSize = 24.sp, fontWeight = FontWeight.SemiBold) }
                return@Column
            }
            // The trip is over: say so, instead of "갈 곳이 없어요" (which reads like nothing was planned).
            val dayDone = trip != null && trip.stops.isNotEmpty() && trip.next == null && nextName == null
            if (dayDone) {
                Text(trip!!.title, color = Soft, fontSize = 20.sp)
                Text("오늘 일정 끝", color = Ink, fontSize = 44.sp, fontWeight = FontWeight.Black)
                Text("${trip.stops.size}곳 다녀왔어요", color = Ink, fontSize = 24.sp, modifier = Modifier.padding(top = 6.dp))
                Text("사진·한 줄은 폰 기록 탭에서 남겨요", color = Soft, fontSize = 22.sp, modifier = Modifier.padding(top = 10.dp))
            } else {
            Text(if (guiding) "가는 중" else "다음", color = Soft, fontSize = 20.sp)
            Text(nextName ?: "갈 곳이 없어요", color = if (nextName != null) Accent else Soft, fontSize = 44.sp, fontWeight = FontWeight.Black, lineHeight = 52.sp, maxLines = 2)
            listOfNotNull(nextTime?.let { "$it 예정" }, remainText(live, trip, guiding)).joinToString("  ·  ").takeIf { it.isNotEmpty() }?.let {
                Text(it, color = Ink, fontSize = 22.sp, modifier = Modifier.padding(top = 6.dp))
            }
            if (guiding) LegProgress(live, nextName)
            trip?.next?.memo?.let { com.htb.carplan.shared.calendar.StopMemo.split(it).second }?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Soft, fontSize = 22.sp, modifier = Modifier.padding(top = 10.dp), maxLines = 2)
            }
            // What the group said about this place — the thing the driver can't look up while driving.
            comment?.let { (who, text) ->
                Column(Modifier.padding(top = 18.dp).fillMaxWidth().border(1.dp, Line, Corner).padding(16.dp)) {
                    Text("“$text”", color = Ink, fontSize = 24.sp, maxLines = 3)
                    Text(who, color = Soft, fontSize = 17.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
            // While driving, the one after: what comes once this stop is done.
            trip?.remaining?.getOrNull(1)?.takeIf { guiding }?.let { after ->
                HorizontalDivider(color = Line, modifier = Modifier.padding(top = 24.dp, bottom = 16.dp))
                Text("그다음", color = Soft, fontSize = 18.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(after.place.name, color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    after.time?.let { Text("  $it", color = Soft, fontSize = 22.sp) }
                }
                val more = trip.remaining.size - 2
                if (more > 0) Text("그 뒤 ${more}곳 더", color = Soft, fontSize = 18.sp, modifier = Modifier.padding(top = 4.dp))
            }
            }
            Spacer(Modifier.weight(1f))
            later?.let { (d, item) ->
                val label = if (d == today.plusDays(1)) "내일" else "${d.dayOfMonth}일 ${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)}"
                Text("$label  ·  ${item.event.title}${item.event.startTime?.let { "  $it" } ?: ""}", color = Soft, fontSize = 20.sp, maxLines = 1, modifier = Modifier.padding(bottom = 16.dp))
            }
            // Driving: both buttons would only be greyed out; the status band keeps [내비 열기].
            if (!dayDone && !restricted) Button(
                onClick = { if (guiding) onNavi() else onGo() },
                enabled = !restricted && (guiding || nextName != null),
                modifier = Modifier.fillMaxWidth().height(120.dp), shape = Corner,
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White, disabledContainerColor = Color(0xFFD5D8DC), disabledContentColor = Soft),
            ) { Text(if (guiding) "내비 보기" else "안내 시작", fontSize = 32.sp, fontWeight = FontWeight.Bold) }
            if (!dayDone && !restricted) Spacer(Modifier.height(12.dp))
            if (!restricted) Button(
                onClick = onMore, enabled = !restricted, modifier = Modifier.fillMaxWidth().height(76.dp).border(1.dp, Line, Corner), shape = Corner,
                colors = ButtonDefaults.buttonColors(containerColor = Bg, contentColor = Ink),
            ) { Text("더보기", fontSize = 24.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** How much of this leg is behind us: a thin blue line (blue = where the car goes). The leg's length is the
 * largest distance seen since the target changed, so it needs no extra data from the navigation. */
@Composable
private fun LegProgress(live: NaviLive, target: String?) {
    val remain = live.remainMeters ?: return
    var total by remember(target) { mutableStateOf(remain) }
    if (remain > total) total = remain
    val done = if (total <= 0) 0f else (1f - remain.toFloat() / total).coerceIn(0f, 1f)
    Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(6.dp).clip(Corner).background(Line)) {
        Box(Modifier.fillMaxWidth(done).fillMaxHeight().background(Accent))
    }
}

/** "남은 4.2 km · 12분 · 도착 19:43" from the navigation while guiding; the trip's own ETA otherwise. */
private fun remainText(live: NaviLive, trip: TodayTrip?, guiding: Boolean): String? {
    if (!guiding) return null
    val parts = listOfNotNull(
        live.remainMeters?.let { if (it >= 1000) "%.1f km".format(it / 1000.0) else "$it m" },
        live.remainSeconds?.let { "${(it / 60).coerceAtLeast(1)}분" },
        (live.remainSeconds?.let { java.time.LocalTime.now().plusSeconds(it.toLong()) }?.let { "도착 %02d:%02d".format(it.hour, it.minute) } ?: trip?.day?.etaNext?.let { "도착 $it" }),
    )
    return parts.joinToString(" · ").ifEmpty { null }
}

/**
 * 반반 화면 (내비 옆): the day's timeline is the whole screen. No row of buttons — changes are made on the
 * passengers' phones; the car only shows the day and answers the voice questions. One small [더보기] while parked.
 */
@Composable
private fun SplitDayView(trip: TodayTrip?, next: CalendarEvent?, live: NaviLive, guiding: Boolean, restricted: Boolean, prompt: (@Composable () -> Unit)?, onGo: () -> Unit, onMore: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // The rail already names the next place (big, blue); the header only says what the day is and how far.
                Text(trip?.title ?: "오늘", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(remainText(live, trip, guiding) ?: trip?.let { "${it.stops.count { s -> s.visited }}/${it.stops.size}곳 다녀옴" } ?: "", color = Soft, fontSize = 22.sp)
            }
            if (!restricted) TextButton(onClick = onMore) { Text("더보기", color = Soft, fontSize = 22.sp) }
        }
        HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 18.dp))
        prompt?.let { it(); Spacer(Modifier.height(18.dp)) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (trip != null) TripStops(trip, big = true, foldVisited = true)
            else Text(next?.event?.let { "${it.startTime ?: "종일"}  ${it.title}" } ?: "오늘은 일정이 없어요", color = Ink, fontSize = 28.sp)
        }
        if (!guiding && !restricted && (trip?.next != null || next != null)) Button(
            onClick = onGo, modifier = Modifier.fillMaxWidth().height(100.dp), shape = Corner,
            colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White),
        ) { Text("안내 시작", fontSize = 30.sp, fontWeight = FontWeight.Bold) }
    }
}

/** Everything that is not "go to the next place": big tiles, parked only. */
@Composable
private fun MoreDialog(guidingNext: String?, hasTrip: Boolean, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    CarDialog(onDismiss = onDismiss, title = "더보기", width = 900.dp, buttons = { TextButton(onClick = onDismiss) { Text("닫기", fontSize = 22.sp, color = Ink) } }) {
        val tiles = buildList {
            add("qr" to "같이 탄 사람 추가")
            if (hasTrip) add("dart" to "다트 — 동네 뽑기")
            add("add" to "일정 추가")
            add("navi" to "내비 열기")
            if (hasTrip) add("ask" to "다음 목적지 다시 묻기")
            guidingNext?.let { add("arrive" to "$it 도착 처리 (시연)") }
            add("debug" to "시연 메뉴")
        }
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (id, label) ->
                    Button(
                        onClick = { onPick(id) }, modifier = Modifier.weight(1f).height(96.dp).border(1.dp, SheetEdge, Corner), shape = Corner,
                        colors = ButtonDefaults.buttonColors(containerColor = Bg, contentColor = Ink),
                    ) { Text(label, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(label, color = Soft, fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp, bottom = 3.dp))
        Text(value, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
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

/**
 * 9장: the question lives where the eye already is — the right "다음" panel (wide) or above the rail (split) —
 * instead of a band pushing the whole screen down. Two buttons always work (Q21).
 */
@Composable
private fun PromptPanel(p: NextPrompt, compact: Boolean, onGo: () -> Unit, onLater: () -> Unit) {
    val change = p.reason == PromptReason.ROUTE_CHANGE
    val fuel = p.reason == PromptReason.FUEL || p.reason == PromptReason.FUELED || p.reason == PromptReason.PICKUP
    val fueled = p.reason == PromptReason.FUELED
    val pickup = p.reason == PromptReason.PICKUP
    val commute = p.reason == PromptReason.COMMUTE
    val parking = p.reason == PromptReason.PARKING
    val question = when { fuel || commute || parking -> p.message.orEmpty(); change -> "${p.message}. 경로를 바꿀까요?"; else -> "다음 목적지 ${TripNavigator.withRo(p.stop.place.name)} 갈까요?" }
    val hint = when {
        p.listening -> "듣는 중 · \"응\" 또는 \"아니\""
        p.heard != null -> "들은 말 \"${p.heard}\" · 버튼으로 골라 주세요"
        else -> "말하거나 눌러 주세요"
    }
    val yes = when { pickup -> "네, 출발"; fueled -> "네"; fuel -> "들를게요"; change -> "바꾸기"; parking -> "찾아보기"; else -> "가기" }
    val no = when { pickup -> "공유 없이"; fuel || parking -> "아니요"; change -> "그대로"; else -> "나중에" }
    Column(Modifier.fillMaxWidth().background(Ink, Corner).padding(if (compact) 22.dp else 28.dp)) {
        Text(question, color = Color.White, fontSize = if (compact) 30.sp else 36.sp, lineHeight = if (compact) 38.sp else 44.sp, fontWeight = FontWeight.Bold)
        Text(hint, color = Color(0xFFB8BDC2), fontSize = 18.sp, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
        val btn: @Composable (String, Boolean, () -> Unit, Modifier) -> Unit = { label, primary, onClick, m ->
            Button(
                onClick = onClick, modifier = m.height(if (compact) 76.dp else 88.dp).then(if (primary) Modifier else Modifier.border(1.dp, Color(0xFF55595E), Corner)), shape = Corner,
                colors = ButtonDefaults.buttonColors(containerColor = if (primary) Color.White else Ink, contentColor = if (primary) Ink else Color.White),
            ) { Text(label, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
        }
        if (compact) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { btn(yes, true, onGo, Modifier.weight(1f)); btn(no, false, onLater, Modifier.weight(1f)) }
        else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { btn(yes, true, onGo, Modifier.fillMaxWidth()); btn(no, false, onLater, Modifier.fillMaxWidth()) }
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
                    val care = e.categoryId == com.htb.carplan.shared.model.BuiltInCategory.CAR.id
                    Text(if (care) "차량 관리 · ${e.title}" else e.title, color = if (care) Soft else Ink, fontSize = if (hot) 28.sp else if (care) 20.sp else 24.sp, fontWeight = if (hot) FontWeight.Bold else FontWeight.Normal)
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
private fun TripStops(trip: TodayTrip, big: Boolean = false, foldVisited: Boolean = false) {
    val node = if (big) 30.dp else 22.dp; val small = if (big) 24.dp else 18.dp; val rail = if (big) 64.dp else 44.dp
    val fs = if (big) 28.sp else 21.sp; val fsNext = if (big) 34.sp else 21.sp; val gap = if (big) 22.dp else 10.dp; val cyDp = if (big) 22.dp else 15.dp
    val nextId = trip.next?.id
    if (trip.stops.isEmpty()) Text("장소 칸이 아직 없어요 · 폰에서 추가", color = Soft, fontSize = 19.sp)
    val visitedCount = trip.stops.count { it.visited }
    val hidden = if (foldVisited && visitedCount > 1) visitedCount - 1 else 0
    if (hidden > 0) Text("✓  앞의 ${hidden}곳 다녀옴", color = Soft, fontSize = if (big) 20.sp else 16.sp, modifier = Modifier.padding(start = rail, bottom = 10.dp))
    trip.stops.forEachIndexed { i, s ->
        if (i < hidden) return@forEachIndexed
        val isNext = s.id == nextId
        val color = when { s.visited -> Soft; isNext -> Accent; else -> Ink }
        val below = trip.stops.getOrNull(i + 1)
        val fill by animateFloatAsState(if (below?.visited == true) 1f else 0f, tween(450), label = "railFill")
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                Modifier.width(rail).fillMaxHeight().drawBehind {
                    val x = size.width / 2
                    val cy = cyDp.toPx()
                    val w = (if (big) 4 else 3).dp.toPx()
                    val dots = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 7.dp.toPx()), 0f)
                    if (i > 0) drawLine(if (s.visited) RailPassed else Line, Offset(x, 0f), Offset(x, cy), w, StrokeCap.Round, if (s.visited) null else dots)
                    if (below != null) {
                        drawLine(Line, Offset(x, cy), Offset(x, size.height), w, StrokeCap.Round, dots)
                        if (fill > 0f) drawLine(RailPassed, Offset(x, cy), Offset(x, cy + (size.height - cy) * fill), w, StrokeCap.Round)
                    }
                },
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(Modifier.padding(top = (cyDp - node / 2)).size(node), contentAlignment = Alignment.Center) {
                    when {
                        s.visited -> Box(Modifier.size(small).clip(CircleShape).background(RailPassed), contentAlignment = Alignment.Center) { Text("✓", color = Color.White, fontSize = if (big) 14.sp else 11.sp, fontWeight = FontWeight.Bold) }
                        isNext -> Box(Modifier.size(node).clip(CircleShape).background(Accent))
                        else -> Box(Modifier.size(small).clip(CircleShape).background(Bg).border(if (big) 3.dp else 2.dp, Color(0xFFAEB4BA), CircleShape))
                    }
                }
            }
            Column(Modifier.weight(1f).padding(bottom = if (below != null) gap else 0.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    s.time?.let { Text(it, color = if (isNext) Accent else Soft, fontSize = if (big) 22.sp else 21.sp, modifier = Modifier.width(if (big) 84.dp else 64.dp)) }
                    Text(s.place.name, color = color, fontSize = if (isNext) fsNext else fs, fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                    if (isNext && !big) Text("  다음", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                if (s.memo.isNotBlank() && (isNext || !big)) {
                    val (tag, rest) = com.htb.carplan.shared.calendar.StopMemo.split(s.memo)
                    Row(Modifier.padding(start = if (s.time != null) (if (big) 84.dp else 64.dp) else 0.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        tag?.let { Text(it, color = Soft, fontSize = if (big) 15.sp else 12.sp, modifier = Modifier.border(1.dp, Line, Corner).padding(horizontal = 6.dp, vertical = 1.dp)) }
                        if (rest.isNotBlank()) Text(rest, color = Soft, fontSize = if (big) 20.sp else 17.sp, maxLines = 1, modifier = Modifier.padding(start = if (tag != null) 8.dp else 0.dp))
                    }
                }
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
private fun InviteQrDialog(group: Group?, seated: Boolean, hasTrip: Boolean, onNewGroup: () -> Unit, onDismiss: () -> Unit) {
    CarDialog(
        onDismiss = onDismiss,
        title = if (seated) "같이 타셨나요? QR 찍고 들어오세요" else if (group == null) "같이 탄 사람 추가" else "${group.name}에 들어오기",
        buttons = {
            if (group == null) { TextButton(onClick = onDismiss) { Text("닫기", fontSize = 22.sp, color = Soft) }; TextButton(onClick = onNewGroup) { Text("새 그룹", fontSize = 22.sp, color = Ink, fontWeight = FontWeight.Bold) } }
            else TextButton(onClick = onDismiss) { Text("닫기", fontSize = 22.sp, color = Ink) }
        },
    ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (group == null) {
                    Text(if (hasTrip) "오늘 여행은 혼자 쓰는 일정이라 들어올 그룹이 없어요. 새 그룹을 만들면 QR이 떠요." else "오늘 걸린 여행이 없어요. 새 그룹을 만들면 QR이 떠요.", fontSize = 20.sp, color = Soft)
                } else {
                    val link = InviteLinks.url(group.inviteCode)
                    val bitmap = remember(link) { qrBitmap(link, 560) }
                    Image(bitmap.asImageBitmap(), contentDescription = "초대 QR", modifier = Modifier.size(280.dp))
                    Text("폰으로 QR을 찍으면 바로 그룹에 들어와요", fontSize = 20.sp, color = Ink)
                    Text("코드  ${group.inviteCode}   ·   ${group.memberIds.size}명", fontSize = 20.sp, color = Soft)
                }
            }
    }
}

/** 12장: the three cheapest stations near the car; one tap makes it the next waypoint. */
@Composable
private fun StationDialog(stations: List<GasStation>, onPick: (GasStation) -> Unit, onDismiss: () -> Unit) {
    CarDialog(onDismiss = onDismiss, title = "가까운 최저가 주유소", buttons = { TextButton(onClick = onDismiss) { Text("닫기", fontSize = 22.sp, color = Soft) } }) {
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
    }
}

/** 시뮬레이션이 도는 동안 맨 위 한 줄: 지금 무슨 장면인지 + 일시정지·배속·끝. */
@Composable
private fun SimBar(sim: SimState, onPause: () -> Unit, onSpeed: () -> Unit, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("시뮬레이션", color = Soft, fontSize = 18.sp)
        Text(sim.phase, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        sim.remainMeters?.let { Text(if (it >= 1000) "%.1f km".format(it / 1000.0) else "$it m", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
        if (!sim.viaConsole) Text("콘솔 없이", color = Soft, fontSize = 16.sp)
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onPause, shape = Corner, border = androidx.compose.foundation.BorderStroke(1.dp, Line)) { Text(if (sim.paused) "계속" else "일시정지", color = Ink, fontSize = 18.sp) }
        OutlinedButton(onClick = onSpeed, shape = Corner, border = androidx.compose.foundation.BorderStroke(1.dp, Line)) { Text(if (sim.speedX == 1) "2배속" else "1배속", color = Ink, fontSize = 18.sp) }
        TextButton(onClick = onStop) { Text("끝", color = Soft, fontSize = 18.sp) }
    }
    HorizontalDivider(color = Line)
}

/** 20장: demo controls — fuel %, car type, fake drive, arrival. */
@Composable
private fun DebugDialog(v: CarState, vm: CarHomeViewModel, onDismiss: () -> Unit) {
    CarDialog(onDismiss = onDismiss, title = "시연 메뉴", buttons = { TextButton(onClick = onDismiss) { Text("닫기", fontSize = 22.sp, color = Ink) } }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("연료/배터리  지금 ${v.fuelPercent?.toInt() ?: "—"}%  ·  ${if (v.isEv == true) "전기차" else if (v.isEv == false) "연료차" else "종류 모름"}", fontSize = 18.sp, color = Soft)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10.0, 20.0, 50.0, 100.0).forEach { p -> FilterChip(selected = v.fuelPercent == p, onClick = { vm.setFuelOverride(p) }, shape = Corner, colors = chipColors(), label = { Text("${p.toInt()}%", fontSize = 18.sp, modifier = Modifier.padding(6.dp)) }) }
                    FilterChip(selected = false, onClick = { vm.setFuelOverride(null) }, shape = Corner, colors = chipColors(), label = { Text("실제값", fontSize = 18.sp, modifier = Modifier.padding(6.dp)) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = v.isEv == false, onClick = { vm.setEvOverride(false) }, shape = Corner, colors = chipColors(), label = { Text("연료차로", fontSize = 18.sp, modifier = Modifier.padding(6.dp)) })
                    FilterChip(selected = v.isEv == true, onClick = { vm.setEvOverride(true) }, shape = Corner, colors = chipColors(), label = { Text("전기차로", fontSize = 18.sp, modifier = Modifier.padding(6.dp)) })
                    FilterChip(selected = false, onClick = { vm.setEvOverride(null) }, shape = Corner, colors = chipColors(), label = { Text("실제값", fontSize = 18.sp, modifier = Modifier.padding(6.dp)) })
                }
                HorizontalDivider(color = Line)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { vm.playSim(); onDismiss() }, shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Ink)) { Text("시뮬레이션 재생", fontSize = 18.sp) }
                    TextButton(onClick = { vm.stopSim() }) { Text("멈춤", fontSize = 18.sp, color = Soft) }
                    TextButton(onClick = { vm.makeTestTrip() }) { Text(if (vm.trip.value?.title == "시험 여행") "시험 여행 처음으로" else "오늘 시험 여행 만들기", fontSize = 18.sp, color = Ink) }
                }
                Text("오늘 여행을 처음부터: 타기 → D → 실제 도로로 이동 → P → 문 열고 내림. 기어·문·좌석은 Virtual Console로 보내요.", fontSize = 16.sp, color = Soft)
                Text("연료 10~20%로 두면 주유 질문이 나와요. 전기차면 충전소 문구.", fontSize = 16.sp, color = Soft)
                HorizontalDivider(color = Line)
                val split by vm.previewSplit.collectAsStateWithLifecycle()
                FilterChip(selected = split, onClick = { vm.previewSplit.value = !split }, shape = Corner, colors = chipColors(), label = { Text("반반 미리보기 (내비 옆 화면)", fontSize = 18.sp, modifier = Modifier.padding(6.dp)) })
            }
    }
}

/** zxing core → black-on-white bitmap. */
private fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return bmp
}

/** 차에서 일정 만들기 (Q19): full screen, big fields, hours as buttons, never closed by a stray tap. */
@Composable
private fun CarAddDialog(onSave: (Event) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var dayOffset by remember { mutableStateOf(0L) }
    var hour by remember { mutableStateOf<Int?>(null) }
    var half by remember { mutableStateOf(false) }
    var place by remember { mutableStateOf("") }
    val time = hour?.let { "%02d:%02d".format(it, if (half) 30 else 0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 40.dp, vertical = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("일정 추가", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("취소", fontSize = 24.sp, color = Soft) }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                title, { title = it }, placeholder = { Text("무슨 일정이에요?", fontSize = 28.sp, color = Soft) }, singleLine = true, shape = Corner,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 28.sp, color = Ink), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            Text("언제", color = Soft, fontSize = 18.sp)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("오늘" to 0L, "내일" to 1L, "모레" to 2L).forEach { (label, off) ->
                    FilterChip(selected = dayOffset == off, onClick = { dayOffset = off }, shape = Corner, colors = chipColors(), label = { Text(label, fontSize = 22.sp, modifier = Modifier.padding(10.dp)) })
                }
            }
            Text("몇 시", color = Soft, fontSize = 18.sp, modifier = Modifier.padding(top = 14.dp))
            Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = hour == null, onClick = { hour = null }, shape = Corner, colors = chipColors(), label = { Text("종일", fontSize = 22.sp, modifier = Modifier.padding(10.dp)) })
                (6..22).forEach { h -> FilterChip(selected = hour == h, onClick = { hour = h }, shape = Corner, colors = chipColors(), label = { Text("${h}시", fontSize = 22.sp, modifier = Modifier.padding(10.dp)) }) }
                FilterChip(selected = half, onClick = { half = !half }, enabled = hour != null, shape = Corner, colors = chipColors(), label = { Text("30분", fontSize = 22.sp, modifier = Modifier.padding(10.dp)) })
            }
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                place, { place = it }, placeholder = { Text("장소 (선택) — 이름만 적어도 내비가 찾아요", fontSize = 22.sp, color = Soft) }, singleLine = true, shape = Corner,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 24.sp, color = Ink), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.weight(1f))
            Button(
                enabled = title.isNotBlank(), onClick = {
                    val date = LocalDate.now().plusDays(dayOffset).toString()
                    onSave(Event(title = title.trim(), startDate = date, endDate = date, allDay = time == null, startTime = time, place = place.trim().takeIf { it.isNotEmpty() }?.let { Place(name = it) }))
                },
                modifier = Modifier.fillMaxWidth().height(96.dp), shape = Corner,
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White, disabledContainerColor = Color(0xFFD5D8DC), disabledContentColor = Soft),
            ) { Text(listOfNotNull(listOf("오늘", "내일", "모레")[dayOffset.toInt()], time ?: "종일", title.ifBlank { null }).joinToString("  ") + "  추가", fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
        }
    }
}

private fun dayTitle(date: LocalDate) =
    "${date.monthValue}월 ${date.dayOfMonth}일 (${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})"
