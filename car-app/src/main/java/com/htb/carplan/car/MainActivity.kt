@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.htb.carplan.car

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
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
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
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.style.TextAlign
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

// Hyundai (Pleos) system look: white ground, near-black text, hairlines, one blue accent, black primary button —
// and the same on black at night (CarTheme.kt). Read inside composables; copy into a local before a draw lambda.
private val Bg: Color @Composable get() = LocalCarColors.current.bg
private val Ink: Color @Composable get() = LocalCarColors.current.ink
private val Soft: Color @Composable get() = LocalCarColors.current.soft
private val Line: Color @Composable get() = LocalCarColors.current.line
private val Accent: Color @Composable get() = LocalCarColors.current.accent
private val Warn: Color @Composable get() = LocalCarColors.current.warn
private val Corner = RoundedCornerShape(4.dp)
private val RailPassed: Color @Composable get() = LocalCarColors.current.railPassed
private val NodeEdge: Color @Composable get() = LocalCarColors.current.nodeEdge
// Dialogs: grey sheet + edge + shadow so they stand off the screen (팀장 2026-10-30).
private val Sheet: Color @Composable get() = LocalCarColors.current.sheet
private val SheetEdge: Color @Composable get() = LocalCarColors.current.sheetEdge
private val Band: Color @Composable get() = LocalCarColors.current.band
private val OnBand: Color @Composable get() = LocalCarColors.current.onBand
private val BandSoft: Color @Composable get() = LocalCarColors.current.bandSoft
private val BandEdge: Color @Composable get() = LocalCarColors.current.bandEdge
private val OnInk: Color @Composable get() = LocalCarColors.current.onInk
private val Disabled: Color @Composable get() = LocalCarColors.current.disabled

class MainActivity : ComponentActivity() {
    private val vm: CarHomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No notification permission asked (팀장 2026-11-11): the only notification is TripSyncService's "일정 동기화"
        // line, and a foreground service runs without it — the car speaks and shows bands instead.
        setContent {
            val forceDark by vm.previewDark.collectAsStateWithLifecycle()
            CarTheme(forceDark = forceDark) {
                Surface(Modifier.fillMaxSize(), color = Bg) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        androidx.compose.runtime.CompositionLocalProvider(LocalCompact provides (maxWidth < CompactBelow)) { CarHome(vm) }
                    }
                }
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
    val nearby by vm.nearby.collectAsStateWithLifecycle()
    var debug by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var dartOpen by remember { mutableStateOf(false) }
    val dart by vm.dart.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var addDate by remember { mutableStateOf(LocalDate.now()) }
    var qr by remember { mutableStateOf(false) }
    var seatedPrompt by remember { mutableStateOf(false) }
    /** Someone sat down while a question was on screen: the QR waits until it is answered. */
    var seatedWaiting by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var pickTrip by remember { mutableStateOf(false) }
    val allTrips by vm.tripsToday.collectAsStateWithLifecycle()
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

    // Pleos: no animation while driving — every CarMotion helper snaps then.
    androidx.compose.runtime.CompositionLocalProvider(LocalMotion provides !vehicle.drivingRestricted) {
    Column(Modifier.fillMaxSize()) {
        val passengers by vm.passengerCount.collectAsStateWithLifecycle()
        val careDue by vm.careDue.collectAsStateWithLifecycle()
        StatusBand(car, vehicle, qrGroup?.name, passengers, careDue, onNavi = vm::openNavigation, onListen = vm::listen, onGroup = { qr = true }, onDemo = { debug = true }, onSettings = { settings = true })
        val voiceState by vm.voiceState.collectAsStateWithLifecycle()
        CarAppear(voiceState.listening || voiceState.choices) { VoiceBar(voiceState, onCommand = vm::runCommand, onClose = vm::closeVoice) }
        val sim by vm.sim.collectAsStateWithLifecycle()
        if (sim.running) SimBar(sim, onPause = vm::pauseSim, onSpeed = vm::speedSim, onStop = vm::stopSim, onRemoveStop = vm::testRemoveStop)
        // The driver gets one plain line; the raw message goes to the log (차 화면 원칙).
        error?.let { raw ->
            LaunchedEffect(raw) { android.util.Log.w("CarHome", raw) }
            val plain = when {
                raw.contains("PERMISSION", true) -> "서버 연결에 잠깐 문제가 있어요"
                raw.contains("UNAVAILABLE", true) || raw.contains("network", true) -> "인터넷이 잠깐 끊겼어요"
                else -> "문제가 생겼어요 · 잠시 뒤 다시 해 봐요"
            }
            Text(plain, color = Warn, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp))
        }
        var lastNotice by remember { mutableStateOf("") }
        notice?.let { lastNotice = it }
        CarAppear(notice != null) { Text(lastNotice, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp)) }
        if (car?.ownerUid == null) {
            LinkCodeScreen(car?.linkCode, onDemo = vm::linkDemoOwner.takeIf { vm.demoAvailable })
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
        // 주차 중엔 폰 앱처럼 (팀장 2026-10-31): a tab bar at the bottom (넷플릭스 하단 바 참고). Driving: only 오늘, no bar.
        var tab by remember { mutableStateOf(CarTab.TODAY) }
        var tapAgain by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        LaunchedEffect(restricted) { if (restricted) tab = CarTab.TODAY }
        Column(Modifier.fillMaxSize()) {
        // Tabs slide sideways in the bar's direction (팀장 2026-10-31). Parked only: driving has no tabs, so no motion.
        androidx.compose.animation.AnimatedContent(
            targetState = tab, modifier = Modifier.weight(1f).fillMaxWidth(), label = "tabs",
            transitionSpec = {
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                val spec = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(TAB_SLIDE_MS)
                (androidx.compose.animation.slideInHorizontally(spec) { w -> dir * w } togetherWith androidx.compose.animation.slideOutHorizontally(spec) { w -> -dir * w })
            },
        ) { shown ->
        androidx.compose.runtime.CompositionLocalProvider(LocalCarTabTapAgain provides tapAgain) {
        Row(Modifier.fillMaxSize()) {
        if (shown == CarTab.CALENDAR) CarCalendar(vm, onAdd = { d -> addDate = d; adding = true }, onGoTo = { e -> vm.goTo(e); tab = CarTab.TODAY })
        else if (shown == CarTab.PEOPLE) CarPeople(vm)
        else if (shown == CarTab.RECORDS) CarRecords(vm)
        else if (shown == CarTab.VEHICLE) CarVehicle(vm)
        else BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            // Two shapes (팀장 2026-10-30): alone → wide; next to the navigation (half the app area) → the day's timeline, big.
            val wide = maxWidth >= 1100.dp
            // Our window is half the app area → the driver put us next to the navigation (Pleos 1:1 split).
            LaunchedEffect(wide) { CarRuntime.splitScreen = !wide }
            val compact = !wide || forceSplit
            Row(Modifier.fillMaxSize()) {
                if (forceSplit && wide) Box(Modifier.weight(1f).fillMaxHeight().background(LocalCarColors.current.placeholder), contentAlignment = Alignment.Center) {
                    Text("다른 앱 자리\n(내비·음악 등, 반반 미리보기)", color = Soft, fontSize = 26.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    // Parked: a question about one place opens on the rail at that place; the rest keep the band on top.
                    val railFuel = vm.fuelSuggestion.collectAsStateWithLifecycle().value?.takeIf { it.eventId == trip?.eventId }
                    val railNeed = vm.fuelNeed.collectAsStateWithLifecycle().value?.takeIf { it.eventId == trip?.eventId && it.afterStopId != trip?.stops?.lastOrNull()?.id }
                    val askOnRail = prompt?.takeIf { p ->
                        !restricted && trip != null && (
                            (p.reason in RAIL_ASK_START && trip!!.stops.any { it.id == p.stop.id && !it.visited }) ||
                                (p.reason == PromptReason.FUEL && (railFuel != null || railNeed != null)))
                    }
                    val promptSlot: (@Composable () -> Unit)? = prompt?.takeIf { askOnRail == null }?.let { p -> { CarEnter(Entry.FADE, key = p.reason to p.stop.id) { PromptPanel(p, compact = compact, onGo = vm::go, onLater = vm::later) } } }
                    if (compact) SplitDayView(trip, vm.dayComments.collectAsStateWithLifecycle().value, vm.fuelSuggestion.collectAsStateWithLifecycle().value?.takeIf { it.eventId == trip?.eventId }, vm::goViaSuggested, vm.fuelNeed.collectAsStateWithLifecycle().value, vm::findFuelOnRoute, askOnRail, vm::go, vm::later, trip?.let { fuelLine(it, vehicle, vm.fuelUsage.collectAsStateWithLifecycle().value) }, next, live, guiding, vehicle.drivingRestricted, promptSlot, otherTrips = (allTrips.size - 1).coerceAtLeast(0), onOtherTrip = { pickTrip = true }, suggested = vm.suggested.collectAsStateWithLifecycle().value, onGoSuggested = vm::goSuggestedTrip, onGo = { if (tripNext != null) vm.go() else next?.let(vm::goTo) }, onMore = { more = true })
                    else HomeView(
                        today = today, week = week, vm = vm, trip = trip, next = next, guiding = guiding, live = live, prompt = promptSlot, ask = askOnRail, restricted = restricted, comment = nextComment, moving = vehicle.gear == com.htb.carplan.shared.model.Gear.D, onOtherTrip = { pickTrip = true }, onAdd = { adding = true }, onEdit = { editing = true },
                        onGo = { if (tripNext != null) vm.go() else next?.let(vm::goTo) },
                        onNavi = { vm.openNavigation() },
                        onMore = { more = true },
                    )
                }
            }
        // Pleos 주행 제한: buttons are disabled while moving (view only); the voice question's two buttons stay usable.
        }
        }
        }
        }
        if (!restricted) Box(Modifier.fillMaxWidth().padding(bottom = 14.dp, top = 4.dp), contentAlignment = Alignment.Center) {
            CarTabBar(tab, onTab = { if (it == tab) tapAgain++ else tab = it })
        }
        }
    }
    }

    if (more && !vehicle.drivingRestricted) MoreDialog(
        guidingNext = if (guiding) trip?.next?.place?.name else null,
        hasTrip = trip != null,
        onPick = { what ->
            more = false
            when (what) {
                "find:fuel" -> vm.runCommand(VoiceCommand.FUEL); "find:parking" -> vm.runCommand(VoiceCommand.PARKING); "find:food" -> vm.runCommand(VoiceCommand.FOOD)
                "qr" -> qr = true; "dart" -> dartOpen = true; "add" -> adding = true; "navi" -> vm.openNavigation()
                "ask" -> vm.askNow(); "edit" -> editing = true; "debug" -> debug = true; "arrive" -> vm.debugArrive()
            }
        },
        onDismiss = { more = false },
    )
    if (adding && !vehicle.drivingRestricted) {
        CarAddDialog(vm, date = addDate, onSave = { vm.addEvent(it); adding = false }, onDismiss = { adding = false; addDate = LocalDate.now() })
    }
    if (editing && !vehicle.drivingRestricted) CarTripEdit(vm, onDismiss = { editing = false })
    if (pickTrip && trip != null && !vehicle.drivingRestricted) CarDialog(onDismiss = { pickTrip = false }, title = "어느 여행으로 갈까요?", buttons = { CarCloseButton(onClick = { pickTrip = false }) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        allTrips.forEach { e ->
                            val on = e.event.id == trip?.eventId
                            Button(
                                onClick = { pickTrip = false; if (!on) vm.chooseTrip(e.groupId, e.event.id) }, modifier = Modifier.fillMaxWidth().height(76.dp).border(1.dp, if (on) Ink else Line, Corner), shape = Corner,
                                colors = ButtonDefaults.buttonColors(containerColor = Bg, contentColor = Ink),
                            ) { Text((if (on) "지금 · " else "") + e.event.title, fontSize = 24.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1) }
                        }
                        Text("고르면 그 여행의 칸을 따라가요. 안내 중이던 경로는 멈춰요.", fontSize = 20.sp, color = Soft)
                    }
    }
    if (stations.isNotEmpty()) StationDialog(stations, onPick = vm::goViaStation, onDismiss = vm::dismissStations)
    nearby?.let { n -> NearbyDialog(n, onPick = { vm.goViaNearby(it, n.kind) }, onDismiss = vm::dismissNearby) }
    if (debug) DebugDialog(vehicle, vm, onDismiss = { debug = false })
    if (settings && !vehicle.drivingRestricted) SettingsDialog(onDemo = { settings = false; debug = true }, onDismiss = { settings = false })
    if (dartOpen && !vehicle.drivingRestricted) CarDartGame(vm, onDismiss = { dartOpen = false })
    if (qr && !vehicle.drivingRestricted) {
        InviteQrDialog(qrGroup, seated = seatedPrompt, tripTitle = trip?.title, friendCode = vm.ownerFriendCode, ownerName = vm.ownerName, onShareTrip = vm::inviteToTodayTrip, onDismiss = { qr = false; seatedPrompt = false })
    }
}

/** All car dialogs: dark scrim, grey sheet with an edge and shadow, white cards inside — readable over the white screen. */
/** 설정 (팀장 2026-11-02): for now one tile, the 시연 메뉴 (시뮬레이션 재생 · 시험 여행 …). Parked only. */
@Composable
private fun SettingsDialog(onDemo: () -> Unit, onDismiss: () -> Unit) {
    CarDialog(onDismiss = onDismiss, title = "설정", buttons = { TextButton(onClick = onDismiss) { Text("닫기", fontSize = 22.sp, color = Ink) } }) {
        Button(
            onClick = onDemo, modifier = Modifier.fillMaxWidth().height(96.dp).border(1.dp, SheetEdge, Corner), shape = Corner,
            colors = ButtonDefaults.buttonColors(containerColor = Bg, contentColor = Ink),
        ) { Text("시연 메뉴", fontSize = 24.sp, fontWeight = FontWeight.SemiBold) }
        Text("시뮬레이션 재생 · 시험 여행 만들기 · 연료 바꾸기 등", fontSize = 20.sp, color = Soft, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
internal fun CarDialog(onDismiss: () -> Unit, title: String, width: Dp = 780.dp, buttons: @Composable RowScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // A bump on the road must not close a window: only its [닫기] does (no tap-outside dismiss).
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Box(
            Modifier.fillMaxSize().background(Color(0x8C131417)),
            contentAlignment = Alignment.Center,
        ) {
            CarEnter(Entry.SHEET) {
            Column(
                Modifier.widthIn(max = width).fillMaxWidth(if (LocalCompact.current) 0.94f else 0.72f).shadow(24.dp, Corner).clip(Corner).background(Sheet).border(1.dp, SheetEdge, Corner)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { }
                    .padding(28.dp),
            ) {
                Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(16.dp))
                content()
                Spacer(Modifier.height(16.dp))
                // Driver side first: the window's buttons sit bottom-left, [닫기] as a full-size button.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { buttons() }
            }
            }
        }
    }
}

@Composable
private fun chipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors(
    containerColor = Bg, labelColor = Ink, selectedContainerColor = Ink, selectedLabelColor = OnInk,
)

@Composable
private fun StatusBand(car: Car?, v: CarState, groupName: String?, passengers: Int, careDue: String?, onNavi: () -> Unit, onListen: () -> Unit, onGroup: () -> Unit, onDemo: () -> Unit, onSettings: () -> Unit) {
    // Half screen: only fuel (+ 주유 필요), the group and [말하기] — range, 정비 and the navigation button stay off
    // (the navigation is right next to us).
    val compact = LocalCompact.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (compact) 20.dp else 32.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 시연 메뉴 is not for drivers: hold the logo to open it (C5).
        Text("carplan", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onDemo))
        Stat(if (v.isEv == true) "배터리" else "연료", v.fuelPercent?.let { "${it.toInt()}%" } ?: "—")
        // At or under the alert level (설정 cars.fuelAlertPercent): say it in words, no extra colour (one accent only).
        val alertAt = car?.fuelAlertPercent?.toDouble() ?: 20.0
        if ((v.fuelPercent ?: 101.0) <= alertAt) Text(if (v.isEv == true) "충전 필요" else "주유 필요", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.border(1.dp, Ink, Corner).padding(horizontal = 8.dp, vertical = 2.dp))
        if (!compact) v.rangeKm?.takeIf { it > 0 }?.let { Stat("주행가능", "${it.toInt()} km") }
        if (!compact) careDue?.let { Stat("정비", it) }
        Spacer(Modifier.weight(1f))
        // Tap the group to show its QR (7장) — the band is where the driver already looks.
        groupName?.let { Box(Modifier.weight(1f, fill = false).clickable(enabled = !v.drivingRestricted, onClick = onGroup)) { if (compact) Text(it, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) else Stat("그룹", it) } }
        if (passengers > 0 && !compact) Stat("동승자", "${passengers}명")
        // ⑧ 음성 명령: always there, driving included — speaking is how the driver changes things on the move.
        // Voice is the main way to act while driving: a full-size button, filled so it reads first in the band.
        Button(onClick = onListen, shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = OnInk), modifier = Modifier.heightIn(min = 76.dp).widthIn(min = 160.dp)) {
            Text("말하기", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        // [설정] next to [말하기] (팀장 2026-11-02): parked only, holds the 시연 메뉴 for now.
        if (!v.drivingRestricted) {
            OutlinedButton(onClick = onSettings, shape = Corner, border = androidx.compose.foundation.BorderStroke(1.dp, SheetEdge), modifier = Modifier.heightIn(min = 76.dp).widthIn(min = 120.dp)) {
                Text("설정", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
        }
        // Driving: the screen is view-only (바꿀 건 동승자 폰에서); the one thing left to press is the navigation.
        if (v.drivingRestricted) {
            if (!compact) Text("주행 중 · 보기만", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (!compact) TextButton(onClick = onNavi) { Text("내비 열기", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
    }
    HorizontalDivider(color = Line)
}

/**
 * 차 첫 화면 (팀장 2026-10-30: "표현에 집중, 누르는 건 간결하게"). Left: today's rail, big. Right: the one
 * thing to do next — where, when, one huge button. Everything else lives behind [더보기] (parked only).
 */
@Composable
private fun HomeView(today: LocalDate, week: List<CalendarEvent>, vm: CarHomeViewModel, trip: TodayTrip?, next: CalendarEvent?, guiding: Boolean, live: NaviLive, prompt: (@Composable () -> Unit)?, ask: NextPrompt? = null, restricted: Boolean, comment: Pair<String, String>?, moving: Boolean, onOtherTrip: () -> Unit, onAdd: () -> Unit, onEdit: () -> Unit, onGo: () -> Unit, onNavi: () -> Unit, onMore: () -> Unit) {
    // Parked at a stop with the route still on: the next place is "다음", not "가는 중".
    val goingLabel = if (guiding && moving) "가는 중" else "다음"
    val todayEvents = vm.eventsOn(today, week)
    val others = todayEvents.filter { it.event.id != trip?.eventId }
    // 11장: while someone is still to be collected, the car is going to them, not to the first stop.
    val pickupFirst = trip?.nextPickup?.takeIf { guiding }
    // A place picked on the way (주차장·주유소·맛집) or a single event comes first: that is where the car is going.
    val detour by vm.detour.collectAsStateWithLifecycle()
    val nextName = detour?.takeIf { guiding }?.place?.name ?: pickupFirst?.let { "${it.name.ifBlank { "일행" }} 태우러 · ${it.place.name}" } ?: trip?.next?.place?.name ?: next?.event?.place?.name
    val nextTime = trip?.next?.time ?: next?.event?.startTime
    val later = (1..6L).asSequence().flatMap { d -> vm.eventsOn(today.plusDays(d), week).map { today.plusDays(d) to it } }.firstOrNull()
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1.45f).fillMaxHeight().padding(horizontal = 40.dp, vertical = 28.dp).verticalScroll(rememberScrollState())) {
            Text("오늘  ${dayTitle(today)}", color = Soft, fontSize = 20.sp)
            Spacer(Modifier.height(10.dp))
            if (trip != null) {
                // ⑩: more than one trip today — say which one the car follows; parked, the others are one tap away.
                val allTrips by vm.tripsToday.collectAsStateWithLifecycle()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(trip.title, color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
                    if (allTrips.size > 1) {
                        Text("  오늘 여행 ${allTrips.size}개 중", color = Soft, fontSize = 20.sp)
                        if (!restricted) Box(Modifier.padding(start = 12.dp)) { CarCloseButton(onClick = onOtherTrip, label = "다른 여행") }
                    }
                    // ③ 주차 중엔 차에서도 칸을 고친다 (팀장 2026-10-31).
                    if (!restricted) Box(Modifier.padding(start = 12.dp)) { CarCloseButton(onClick = onEdit, label = "칸 편집") }
                }
                // Every place done but another event still ahead today: the right panel moves on to it, so the
                // finished trip says so here (the half view does the same in its header).
                // (Not when the right panel says it big — 평가 2026-11-10: "오늘 일정 끝" twice.)
                if (trip.stops.isNotEmpty() && trip.next == null && nextName != null) Text("오늘 일정 끝 · ${doneLine(trip)}", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                else if (!moving) fuelLine(trip, vm.vehicle.collectAsStateWithLifecycle().value, vm.fuelUsage.collectAsStateWithLifecycle().value)?.let {
                    Text(it, color = Soft, fontSize = 20.sp, modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(18.dp))
                TripStops(trip, big = true, foldVisited = moving, times = drivenTimes(trip, live, guiding), driving = moving, detail = !moving, comments = vm.dayComments.collectAsStateWithLifecycle().value,
                    fuelStop = vm.fuelSuggestion.collectAsStateWithLifecycle().value?.takeIf { it.eventId == trip.eventId }, onFuelVia = vm::goViaSuggested,
                    fuelNeed = vm.fuelNeed.collectAsStateWithLifecycle().value, onFindFuel = vm::findFuelOnRoute, ask = ask, onYes = vm::go, onNo = vm::later)
            } else if (todayEvents.isEmpty()) {
                // A free day (디자인 평가 2026-11-04: "없어요" said twice over an empty screen): say it once, then the week.
                Text("오늘은 비어 있어요", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                val ahead = (1..6L).flatMap { d -> vm.eventsOn(today.plusDays(d), week).map { today.plusDays(d) to it } }.take(5)
                Text("이번 주", color = Soft, fontSize = 20.sp, modifier = Modifier.padding(top = 28.dp, bottom = 4.dp))
                if (ahead.isEmpty()) Text("이번 주도 비어 있어요 · 폰 캘린더에 넣으면 여기로 와요", color = Soft, fontSize = 24.sp)
                ahead.forEach { (d, item) ->
                    val e = item.event
                    val trip = e.categoryId == com.htb.carplan.shared.model.BuiltInCategory.TRIP.id
                    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (d == today.plusDays(1)) "내일" else "${d.monthValue}/${d.dayOfMonth} ${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)}",
                            color = Soft, fontSize = 26.sp, modifier = Modifier.width(150.dp))
                        Text(e.title, color = Ink, fontSize = 26.sp, fontWeight = if (trip) FontWeight.Bold else FontWeight.Normal, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        e.startTime?.let { Text("  $it", color = Soft, fontSize = 24.sp) }
                    }
                }
            }
            if (others.isNotEmpty() && !moving) {
                if (trip != null) HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 22.dp))
                others.forEach { item ->
                    val e = item.event
                    val care = e.categoryId == com.htb.carplan.shared.model.BuiltInCategory.CAR.id
                    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(e.startTime ?: "종일", color = Soft, fontSize = 26.sp, modifier = Modifier.width(96.dp))
                        Text(if (care) "차량 관리 · ${e.title}" else e.title, color = if (care) Soft else Ink, fontSize = 26.sp)
                        e.place?.name?.takeIf { !care }?.let { Text("  $it", color = Soft, fontSize = 26.sp) }
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
                    Text(goingLabel, color = Soft, fontSize = 20.sp, modifier = Modifier.padding(top = 24.dp))
                    Text(nextName, color = Accent, fontSize = 30.sp, fontWeight = FontWeight.Black, maxLines = 1)
                    remainText(live, trip, guiding)?.let { Text(it, color = Ink, fontSize = 26.sp, modifier = Modifier.padding(top = 4.dp)) }
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
                Text("오늘 일정 끝", color = Ink, fontSize = 48.sp, fontWeight = FontWeight.Black)
                Text(doneLine(trip), color = Ink, fontSize = 24.sp, modifier = Modifier.padding(top = 6.dp))
                Text("사진·한 줄은 폰 기록 탭에서 남겨요", color = Soft, fontSize = 26.sp, modifier = Modifier.padding(top = 10.dp))
                // What comes next fills the panel (평가 2026-11-10: it sat empty under the done line).
                later?.let { (d, item) ->
                    HorizontalDivider(color = Line, modifier = Modifier.padding(top = 28.dp, bottom = 16.dp))
                    Text("다음 일정", color = Soft, fontSize = 20.sp)
                    Text(item.event.title, color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
                    val day = if (d == today.plusDays(1)) "내일" else "${d.monthValue}월 ${d.dayOfMonth}일 ${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)}요일"
                    Text(day + (item.event.startTime?.let { "  $it" } ?: ""), color = Soft, fontSize = 24.sp, modifier = Modifier.padding(top = 4.dp))
                }
            } else {
            // D3: 주차 중·일정 없는 날 — 폰이 저장해 둔 추천(기록 취향)을 "오늘 갈 만한 곳"으로. [가기] 한 번이면 오늘 일정 + 경로.
            val suggested by vm.suggested.collectAsStateWithLifecycle()
            val freeDay = trip == null && nextName == null && todayEvents.isEmpty() && !restricted
            if (freeDay && suggested != null) {
                val s = suggested!!
                Text("오늘 갈 만한 곳", color = Soft, fontSize = 20.sp)
                Text(s.title.ifBlank { s.region }, color = Accent, fontSize = 48.sp, fontWeight = FontWeight.Black, lineHeight = 52.sp, maxLines = 2)
                Text(s.stops.joinToString(" → ") { it.name }, color = Ink, fontSize = 26.sp, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                if (s.reason.isNotBlank()) Text(s.reason, color = Soft, fontSize = 20.sp, maxLines = 2, modifier = Modifier.padding(top = 10.dp))
                Button(
                    onClick = vm::goSuggestedTrip, shape = Corner,
                    colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = OnInk),
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp).height(76.dp),
                ) { Text("가기 · 오늘 일정이 돼요", fontSize = 26.sp, fontWeight = FontWeight.Bold) }
            } else {
            Text(if (nextName == null && trip == null) "지금" else goingLabel, color = Soft, fontSize = 20.sp)
            val emptyLine = when {
                trip != null && trip.stops.isEmpty() -> "여행에 칸이 아직 없어요"
                else -> "어디로 갈까요?"
            }
            Text(nextName ?: emptyLine, color = if (nextName != null) Accent else Ink, fontSize = 48.sp, fontWeight = FontWeight.Black, lineHeight = 52.sp, maxLines = 2)
            listOfNotNull(nextTime?.takeIf { !guiding || (live.remainSeconds == null && trip?.day?.etaNext == null) }?.let { "$it 예정" }, remainText(live, trip, guiding)).joinToString("  ·  ").takeIf { it.isNotEmpty() }?.let {
                Text(it, color = Ink, fontSize = 26.sp, modifier = Modifier.padding(top = 6.dp))
            }
            if (guiding) LegProgress(live, nextName)
            trip?.next?.memo?.takeIf { pickupFirst == null }?.let { com.htb.carplan.shared.calendar.StopMemo.split(it).second }?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Soft, fontSize = 26.sp, modifier = Modifier.padding(top = 10.dp), maxLines = 2)
            }
            // What the group said about this place — the thing the driver can't look up while driving.
            comment?.takeIf { pickupFirst == null }?.let { (who, text) ->
                Column(Modifier.padding(top = 18.dp).fillMaxWidth().border(1.dp, Line, Corner).padding(16.dp)) {
                    Text("“$text”", color = Ink, fontSize = 24.sp, maxLines = 3)
                    Text(who, color = Soft, fontSize = 20.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
            // Parked, the rest of the day at a glance (디자인 평가 2026-11-04: the panel was empty under the next place).
            trip?.remaining?.takeIf { !guiding && it.size > 1 && pickupFirst == null }?.let { rest ->
                HorizontalDivider(color = Line, modifier = Modifier.padding(top = 24.dp, bottom = 16.dp))
                Text("오늘 남은 곳 ${rest.size}곳", color = Soft, fontSize = 20.sp)
                Text(rest.drop(1).joinToString(" → ") { it.place.name }, color = Ink, fontSize = 26.sp, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
                rest.last().time?.let { Text("마지막 $it", color = Soft, fontSize = 20.sp, modifier = Modifier.padding(top = 4.dp)) }
            }
            // While driving, the one after: what comes once this stop is done.
            trip?.remaining?.getOrNull(1)?.takeIf { guiding }?.let { after ->
                HorizontalDivider(color = Line, modifier = Modifier.padding(top = 24.dp, bottom = 16.dp))
                Text("그다음", color = Soft, fontSize = 20.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(after.place.name, color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    (drivenTimes(trip, live, guiding)[after.id] ?: after.time)?.let { Text("  $it", color = Soft, fontSize = 26.sp) }
                }
                val more = trip.remaining.size - 2
                if (more > 0) Text("그 뒤 ${more}곳 더", color = Soft, fontSize = 20.sp, modifier = Modifier.padding(top = 4.dp))
            }
            }
            }
            Spacer(Modifier.weight(1f))
            // 혼자여도 버튼 하나로 찾기 (차 화면 원칙): the two everyday searches, one tap each, parked only.
            if (!restricted) Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(VoiceCommand.FUEL to "주유소·충전소", VoiceCommand.PARKING to "주차장").forEach { (c, label) ->
                    OutlinedButton(onClick = { vm.runCommand(c) }, modifier = Modifier.weight(1f).heightIn(min = 76.dp), shape = Corner, border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
                        Text(label, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
            // The left column already lists the week on a free day.
            later?.takeIf { (trip != null || todayEvents.isNotEmpty()) && !dayDone }?.let { (d, item) ->
                val label = if (d == today.plusDays(1)) "내일" else "${d.dayOfMonth}일 ${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)}"
                Text("$label  ·  ${item.event.title}${item.event.startTime?.let { "  $it" } ?: ""}", color = Soft, fontSize = 20.sp, maxLines = 1, modifier = Modifier.padding(bottom = 16.dp))
            }
            // Nothing to go to: say how to get something, and offer the one action the car can do (parked).
            if (nextName == null && !guiding && !dayDone) {
                Text(if (trip != null) "여기서 장소 칸을 넣거나, 폰에서 넣어요" else "내비로 바로 가거나, 일정을 만들어 두세요", color = Soft, fontSize = 24.sp, modifier = Modifier.padding(bottom = 16.dp))
                if (!restricted && trip == null) OutlinedButton(
                    onClick = onNavi, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).heightIn(min = 76.dp), shape = Corner,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Line),
                ) { Text("내비 열기", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold) }
                if (!restricted) Button(
                    onClick = if (trip != null) onEdit else onAdd, modifier = Modifier.fillMaxWidth().height(120.dp), shape = Corner,
                    colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = OnInk),
                ) { Text(if (trip != null) "장소 넣기" else "일정 추가", fontSize = 32.sp, fontWeight = FontWeight.Bold) }
            }
            // Driving: both buttons would only be greyed out; the status band keeps [내비 열기].
            else if (!dayDone && !restricted && (guiding || ask == null)) Button(
                onClick = { if (guiding) onNavi() else onGo() },
                enabled = !restricted && (guiding || nextName != null),
                modifier = Modifier.fillMaxWidth().height(120.dp), shape = Corner,
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = OnInk, disabledContainerColor = Disabled, disabledContentColor = Soft),
            ) { Text(if (guiding) "내비 보기" else "안내 시작", fontSize = 32.sp, fontWeight = FontWeight.Bold) }
            // Full width while driving: the navigation can sit next to carplan instead of covering it.
            if (guiding) Text("화면 가장자리 ⋮를 끌면 내비와 나란히 볼 수 있어요", color = Soft, fontSize = 20.sp, modifier = Modifier.padding(top = 8.dp))
            if (!dayDone && !restricted) Spacer(Modifier.height(12.dp))
            if (!restricted) Button(
                onClick = onMore, enabled = !restricted, modifier = Modifier.fillMaxWidth().height(76.dp).border(1.dp, Line, Corner), shape = Corner,
                colors = ButtonDefaults.buttonColors(containerColor = Bg, contentColor = Ink),
            ) { Text("더보기", fontSize = 24.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/**
 * Rail times as the day is actually going (팀장 2026-10-31): the next stop shows when the navigation expects to
 * arrive, and running late pushes the later stops' times along. Not a warning — the plan just moves.
 */
private fun drivenTimes(trip: TodayTrip, live: NaviLive, guiding: Boolean): Map<String, String> {
    if (!guiding) return emptyMap()
    // The navigation's live time first; between updates, the arrival it last announced (days/{date}.etaNext).
    val eta = (live.stopSeconds ?: live.remainSeconds)?.let { java.time.LocalTime.now().plusSeconds(it.toLong()) }
        ?: trip.day?.etaNext?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() } ?: return emptyMap()
    return com.htb.carplan.shared.calendar.Schedule.asDriven(trip.stops, trip.next?.id, eta)
}

/**
 * Q55 on screen (팀장 2026-10-31): what the rest of the day takes in fuel. After [가기] the road figures from the
 * plan; before, a straight-line guess (×1.3, no API call) marked "약".
 */
private fun fuelLine(trip: TodayTrip, v: com.htb.carplan.shared.model.CarState, usage: FuelUsage?): String? {
    val what = if (v.isEv == true) "배터리" else "연료"
    usage?.takeIf { it.eventId == trip.eventId }?.let { u ->
        // Short = less than the 30 km reserve at some point (FuelPlan.shortfall), not just an empty tank on arrival.
        return "남은 길 ${u.km}km · $what ${u.usedPercent}% 써요 · " + if (u.short) "중간에 넣어야 해요" else "도착 때 ${u.leftPercent.coerceAtLeast(0)}%"
    }
    val pts = trip.remaining.map { it.place }.filter { with(com.htb.carplan.shared.calendar.RouteMath) { it.hasCoords() } }
    if (pts.size < 2) return null
    val meters = pts.zipWithNext().sumOf { (a, b) -> com.htb.carplan.shared.calendar.RouteMath.metersBetween(a.lat, a.lng, b.lat, b.lng) } * 1.3
    val u = com.htb.carplan.shared.calendar.FuelPlan.usage(meters, v.fuelPercent, v.rangeKm?.times(1000)) ?: return null
    return "남은 길 약 ${(meters / 1000).toInt()}km · $what 약 ${u.usedPercent.toInt().coerceAtLeast(1)}% 써요"
}

/**
 * 주유하기 (팀장 2026-10-31): a temporary slot on the rail where the tank runs short — not a real stop, so grey and
 * marked 임시 — and under it the one thing to do about it: [최저가 주유소 찾기] → the list → a waypoint.
 */
@Composable
private fun FuelNeedRow(n: FuelNeed, indent: Dp, showButton: Boolean, onFind: () -> Unit, ask: NextPrompt? = null, onYes: () -> Unit = {}, onNo: () -> Unit = {}) {
    val what = if (n.ev) "충전" else "주유"
    Column(Modifier.fillMaxWidth().padding(start = indent, top = 12.dp, bottom = 4.dp).border(1.dp, Line, Corner).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${what}하기", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("임시", color = Soft, fontSize = 20.sp, modifier = Modifier.padding(start = 10.dp).border(1.dp, Line, Corner).padding(horizontal = 6.dp))
        }
        Text(listOfNotNull(n.percentThen?.let { "이쯤 ${if (n.ev) "배터리" else "연료"} 약 ${it}%" }, "이대로면 모자라요").joinToString(" · "), color = Soft, fontSize = 20.sp)
        if (ask != null) RailAsk(ask, Modifier.padding(top = 8.dp), animate = showButton, onYes, onNo)
        else if (showButton) OutlinedButton(
            onClick = onFind, shape = Corner, border = androidx.compose.foundation.BorderStroke(1.dp, Line),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 76.dp),
        ) { Text(if (n.ev) "가는 길 충전소 찾기" else "최저가 주유소 찾기", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
    }
}

/** The suggested station on the rail: grey, dashed like a plan, with [경유지로] parked. */
@Composable
private fun FuelStopRow(f: FuelSuggestion, indent: Dp, showButton: Boolean, onVia: () -> Unit, ask: NextPrompt? = null, onYes: () -> Unit = {}, onNo: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(start = indent, top = 12.dp, bottom = 4.dp).border(1.dp, Line, Corner).padding(horizontal = 14.dp, vertical = 8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text((if (f.chosen) "주유 · " else "추천 주유 · ") + f.station.name, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(listOfNotNull("${"%,d".format(f.station.price)}원", if (f.chosen) "경유지로 넣었어요" else null, f.percentThen?.let { "연료 ${it}%쯤" }, f.saving?.let { "지금 근처보다 ${it}원 쌈" }).joinToString(" · "), color = Soft, fontSize = 20.sp, maxLines = 1)
        }
        if (showButton && !f.chosen && ask == null) OutlinedButton(onClick = onVia, shape = Corner, modifier = Modifier.heightIn(min = 76.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
            Text("경유지로", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
    if (ask != null) RailAsk(ask, Modifier.padding(top = 8.dp), animate = showButton, onYes, onNo)
    }
}

/** Questions that are about one place on the rail: they open there instead of the band on top. */
private val RAIL_ASK_START = setOf(PromptReason.APP_START, PromptReason.GEAR_D, PromptReason.MANUAL)

/**
 * A question opened on the rail (팀장 2026-10-31): unfolds under its place (0.3 s, parked only — Pleos: no
 * animation while driving), the line to read + [yes] [no]. Voice still asks it; "응/아니" answers it.
 */
@Composable
private fun RailAsk(p: NextPrompt, modifier: Modifier, animate: Boolean, onYes: () -> Unit, onNo: () -> Unit) {
    val shown = remember(p.reason, p.stop.id) { androidx.compose.animation.core.MutableTransitionState(!animate).apply { targetState = true } }
    androidx.compose.animation.AnimatedVisibility(
        visibleState = shown, modifier = modifier,
        enter = androidx.compose.animation.expandVertically(tween(300), expandFrom = Alignment.Top) + androidx.compose.animation.fadeIn(tween(300)),
    ) {
        Column(Modifier.fillMaxWidth().background(Sheet, Corner).border(1.dp, SheetEdge, Corner).padding(16.dp)) {
            val fuel = p.reason == PromptReason.FUEL
            Text(if (fuel) p.message.orEmpty() else "여기로 갈까요?", color = Ink, fontSize = if (fuel) 24.sp else 28.sp, fontWeight = FontWeight.Bold, maxLines = 3)
            p.sub?.let { Text(it, color = Ink, fontSize = 24.sp, modifier = Modifier.padding(top = 2.dp)) }
            Text(
                when { p.listening -> "듣는 중 · \"응\" 또는 \"아니\""; p.heard != null -> "들은 말 \"${p.heard}\" · 버튼으로 골라 주세요"; else -> "말하거나 눌러 주세요" },
                color = Soft, fontSize = 20.sp, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onYes, modifier = Modifier.weight(1f).heightIn(min = 76.dp), shape = Corner,
                    colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = OnInk),
                ) { Text(if (fuel) p.button ?: "들를게요" else "가기", fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                OutlinedButton(
                    onClick = onNo, modifier = Modifier.weight(1f).heightIn(min = 76.dp), shape = Corner, border = androidx.compose.foundation.BorderStroke(1.dp, SheetEdge),
                ) { Text(if (fuel) "닫기" else "나중에", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** "3곳 다녀왔어요" / "2곳 다녀왔어요 · 1곳 건너뜀" */
private fun doneLine(t: TodayTrip): String {
    val skipped = t.stops.count { it.skipped }
    return "${t.stops.size - skipped}곳 다녀왔어요" + if (skipped > 0) " · ${skipped}곳 건너뜀" else ""
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
private fun SplitDayView(trip: TodayTrip?, comments: Map<String, List<StopComment>>, fuelStop: FuelSuggestion?, onFuelVia: () -> Unit, fuelNeed: FuelNeed?, onFindFuel: () -> Unit, ask: NextPrompt?, onYes: () -> Unit, onNo: () -> Unit, fuelLine: String?, next: CalendarEvent?, live: NaviLive, guiding: Boolean, restricted: Boolean, prompt: (@Composable () -> Unit)?, otherTrips: Int = 0, onOtherTrip: () -> Unit = {}, suggested: com.htb.carplan.shared.model.SuggestedTrip? = null, onGoSuggested: () -> Unit = {}, onGo: () -> Unit, onMore: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // The rail already names the next place (big, blue); the header only says what the day is and how far.
                val pickupFirst = trip?.nextPickup?.takeIf { guiding }
                Text(pickupFirst?.let { "${it.name.ifBlank { "일행" }} 태우러 · ${it.place.name}" } ?: trip?.title ?: "오늘", color = if (pickupFirst != null) Accent else Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                val done = trip != null && trip.stops.isNotEmpty() && trip.next == null
                val remain = remainText(live, trip, guiding)
                when {
                    // Driving: how far and when — the one line the driver glances at, so not grey.
                    remain != null -> Text(remain, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    done -> Text("오늘 일정 끝 · ${doneLine(trip!!)}", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    trip != null -> Text("${trip.stops.count { s -> s.visited && !s.skipped }}/${trip.stops.size}곳 다녀옴", color = Soft, fontSize = 26.sp)
                }
                if (!restricted) fuelLine?.let { Text(it, color = Soft, fontSize = 20.sp, maxLines = 1) }
            }
            // ⑩ in the half view too: more than one trip today → one tap to pick another (parked only).
            if (!restricted && otherTrips > 0) CarCloseButton(onClick = onOtherTrip, label = "다른 여행")
            if (!restricted) TextButton(onClick = onMore) { Text("더보기", color = Soft, fontSize = 26.sp) }
        }
        if (guiding) LegProgress(live, trip?.next?.place?.name)
        HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 18.dp))
        prompt?.let { it(); Spacer(Modifier.height(18.dp)) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (trip != null) {
                // The half screen is tall and narrow: room between places, and (parked) the memo·comments·drive like the wide view (팀장 2026-10-31).
                TripStops(trip, big = true, foldVisited = true, times = drivenTimes(trip, live, guiding), driving = restricted, detail = !restricted, comments = comments, roomy = true, fuelStop = fuelStop, onFuelVia = onFuelVia, fuelNeed = fuelNeed, onFindFuel = onFindFuel, ask = ask, onYes = onYes, onNo = onNo)
                if (trip.stops.isNotEmpty() && trip.next == null) Text("사진·한 줄은 폰 기록 탭에서 남겨요", color = Soft, fontSize = 26.sp, modifier = Modifier.padding(top = 18.dp))
            } else {
                Text(next?.event?.let { "${it.startTime ?: "종일"}  ${it.title}" } ?: "오늘은 일정이 없어요", color = Ink, fontSize = 28.sp)
                // D3 반반 모양: 일정 없는 날 "오늘 갈 만한 곳" (주차 중만).
                if (next == null && !restricted && suggested != null) {
                    HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 18.dp))
                    Text("오늘 갈 만한 곳", color = Soft, fontSize = 20.sp)
                    Text(suggested.title.ifBlank { suggested.region }, color = Accent, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(suggested.stops.joinToString(" → ") { it.name }, color = Ink, fontSize = 24.sp, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
                    Button(
                        onClick = onGoSuggested, shape = Corner,
                        colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = OnInk),
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(76.dp),
                    ) { Text("가기 · 오늘 일정이 돼요", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
        // The question on the rail already has [가기].
        if (!guiding && !restricted && ask == null && (trip?.next != null || next != null)) Button(
            onClick = onGo, modifier = Modifier.fillMaxWidth().height(100.dp), shape = Corner,
            colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = OnInk),
        ) { Text("안내 시작", fontSize = 30.sp, fontWeight = FontWeight.Bold) }
    }
}

/** Everything that is not "go to the next place": big tiles, parked only. */
@Composable
private fun MoreDialog(guidingNext: String?, hasTrip: Boolean, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    CarDialog(onDismiss = onDismiss, title = "더보기", width = 900.dp, buttons = { CarCloseButton(onClick = onDismiss) }) {
        val tiles = buildList {
            // C1 (차 화면 원칙: 혼자여도 버튼 하나로 찾기) — each opens the navigation's own search once.
            add("find:food" to "근처 맛집 찾기")
            add("qr" to "같이 탄 사람 추가")
            if (hasTrip) add("dart" to "다트 — 동네 뽑기")
            add("add" to "일정 추가")
            add("navi" to "내비 열기")
            if (hasTrip) add("ask" to "다음 목적지 다시 묻기")
            // The half screen has no room for [칸 편집] next to the title: it lives here too.
            if (hasTrip) add("edit" to "칸 편집")
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
        Text(label, color = Soft, fontSize = 20.sp, modifier = Modifier.padding(end = 8.dp, bottom = 3.dp))
        Text(value, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LinkCodeScreen(code: String?, onDemo: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("폰 앱 → 차량 탭에서 이 코드를 입력하세요", color = Soft, fontSize = 24.sp)
        Spacer(Modifier.height(24.dp))
        Text(code?.chunked(3)?.joinToString(" ") ?: "…", color = Ink, fontSize = 96.sp, fontWeight = FontWeight.Black, letterSpacing = 8.sp)
        Spacer(Modifier.height(16.dp))
        Text("이 차의 차주가 일정의 호스트가 됩니다", color = Soft, fontSize = 20.sp)
        // 시연 계정 (2026-11-11): a judge's car, no phone needed — straight onto the filled demo account.
        onDemo?.let {
            Spacer(Modifier.height(40.dp))
            OutlinedButton(onClick = it, shape = Corner, border = BorderStroke(1.dp, SheetEdge), modifier = Modifier.heightIn(min = 76.dp).width(420.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)) { Text("시연 계정 차로 연결", fontSize = 24.sp, fontWeight = FontWeight.SemiBold) }
            Text("일정·여행·기록이 채워진 시연 계정의 차가 돼요", color = Soft, fontSize = 20.sp, modifier = Modifier.padding(top = 10.dp))
        }
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
    if (p.single) {
        // 차 화면 원칙: a notice with one button — spoken already, nothing to answer; [닫기] just hides it.
        Column(Modifier.fillMaxWidth().background(Band, Corner).border(1.dp, BandEdge, Corner).padding(if (compact) 22.dp else 28.dp)) {
            Text(p.message.orEmpty(), color = OnBand, fontSize = if (compact) 30.sp else 36.sp, lineHeight = if (compact) 38.sp else 48.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onGo, modifier = Modifier.weight(1f).height(if (compact) 76.dp else 88.dp), shape = Corner,
                    colors = ButtonDefaults.buttonColors(containerColor = OnBand, contentColor = Color(0xFF111111)),
                ) { Text(p.button ?: "찾기", fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                TextButton(onClick = onLater, modifier = Modifier.padding(start = 8.dp)) { Text("닫기", color = BandSoft, fontSize = 26.sp) }
            }
        }
        return
    }
    val question = when { fuel || commute || parking -> p.message.orEmpty(); change -> "${p.message}. 경로를 바꿀까요?"; else -> "다음 목적지 ${TripNavigator.withRo(p.stop.place.name)} 갈까요?" }
    // ★1: the start question carries "지금 출발하면 10:12 도착" in [NextPrompt.message].
    val startLine = p.sub
    val hint = when {
        p.listening -> "듣는 중 · \"응\" 또는 \"아니\""
        p.heard != null -> "들은 말 \"${p.heard}\" · 버튼으로 골라 주세요"
        else -> "말하거나 눌러 주세요"
    }
    val yes = when { pickup -> "네, 출발"; fueled -> "네"; fuel -> "들를게요"; change -> "바꾸기"; parking -> "찾아보기"; else -> "가기" }
    val no = when { pickup -> "공유 없이"; fuel || parking -> "아니요"; change -> "그대로"; else -> "나중에" }
    Column(Modifier.fillMaxWidth().background(Band, Corner).border(1.dp, BandEdge, Corner).padding(if (compact) 22.dp else 28.dp)) {
        Text(question, color = OnBand, fontSize = if (compact) 30.sp else 36.sp, lineHeight = if (compact) 38.sp else 48.sp, fontWeight = FontWeight.Bold)
        startLine?.let { Text(it, color = OnBand, fontSize = if (compact) 24.sp else 28.sp, modifier = Modifier.padding(top = 6.dp)) }
        Text(hint, color = BandSoft, fontSize = 20.sp, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
        val btn: @Composable (String, Boolean, () -> Unit, Modifier) -> Unit = { label, primary, onClick, m ->
            Button(
                onClick = onClick, modifier = m.height(if (compact) 76.dp else 88.dp).then(if (primary) Modifier else Modifier.border(1.dp, BandEdge, Corner)), shape = Corner,
                colors = ButtonDefaults.buttonColors(containerColor = if (primary) OnBand else Band, contentColor = if (primary) Color(0xFF111111) else OnBand),
            ) { Text(label, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
        }
        if (compact) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { btn(yes, true, onGo, Modifier.weight(1f)); btn(no, false, onLater, Modifier.weight(1f)) }
        else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { btn(yes, true, onGo, Modifier.fillMaxWidth()); btn(no, false, onLater, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun SectionTitle(text: String) = Text(text, color = Soft, fontSize = 20.sp, modifier = Modifier.padding(bottom = 10.dp))

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
                    if (isTrip) TripStops(trip!!) else e.place?.name?.let { Text(it, color = if (isNext) Accent else Soft, fontSize = 24.sp) }
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
private fun TripStops(trip: TodayTrip, big: Boolean = false, foldVisited: Boolean = false, times: Map<String, String> = emptyMap(), driving: Boolean = false, detail: Boolean = false, comments: Map<String, List<StopComment>> = emptyMap(), roomy: Boolean = false, fuelStop: FuelSuggestion? = null, onFuelVia: () -> Unit = {}, fuelNeed: FuelNeed? = null, onFindFuel: () -> Unit = {}, ask: NextPrompt? = null, onYes: () -> Unit = {}, onNo: () -> Unit = {}) {
    // A found station (Q55) takes the 주유하기 slot's place; until then the slot itself, with the search button.
    val need = fuelNeed?.takeIf { fuelStop == null && it.eventId == trip.eventId }
    // detail (주차 중, 팀장 2026-10-31: 폰 타임라인처럼): expected times for blank slots, the planned time under a moved
    // one, every memo, the group's comments (read-only) and the drive between places — straight-line, no API call.
    val legs = remember(trip.stops) {
        trip.stops.zipWithNext().associate { (a, b) ->
            a.id to if (with(com.htb.carplan.shared.calendar.RouteMath) { a.place.hasCoords() && b.place.hasCoords() })
                com.htb.carplan.shared.calendar.RouteMath.metersBetween(a.place.lat, a.place.lng, b.place.lat, b.place.lng).let { m -> m to com.htb.carplan.shared.calendar.RouteMath.estimateSeconds(m) }
            else null
        }
    }
    // A guess already in the past says nothing for a place still ahead (the plan ran long): leave it blank.
    val estimated = remember(trip.stops, legs) {
        if (!detail) emptyMap() else {
            val now = java.time.LocalTime.now().toString().take(5)
            com.htb.carplan.shared.calendar.Schedule.estimate(trip.stops, { id -> legs[id]?.second })
                .filter { (id, t) -> trip.stops.firstOrNull { it.id == id }?.visited == true || t >= now }
        }
    }
    val timeW = if (detail) 116.dp else if (big) 100.dp else 64.dp
    val node = if (big) 30.dp else 22.dp; val small = if (big) 24.dp else 18.dp; val rail = if (big) 64.dp else 44.dp
    val fs = if (big) 28.sp else 24.sp; val fsNext = if (big) 36.sp else 24.sp; val gap = if (roomy) 44.dp else if (big) 30.dp else 10.dp; val cyDp = if (big) 22.dp else 15.dp
    val nextId = trip.next?.id
    val railPassed = RailPassed; val line = Line // drawBehind is not composable
    if (trip.stops.isEmpty()) Text("장소 칸이 아직 없어요 · [칸 편집]에서 넣어요", color = Soft, fontSize = 24.sp)
    val visitedCount = trip.stops.count { it.visited }
    val hidden = if (foldVisited && visitedCount > 1) visitedCount - 1 else 0
    if (hidden > 0) Text("✓  앞의 ${hidden}곳 다녀옴", color = Soft, fontSize = if (big) 20.sp else 20.sp, modifier = Modifier.padding(start = rail, bottom = 10.dp))
    // 차 화면 원칙: while moving, only what the driver needs — the next stop and the one after; the rest is for the phones.
    val nextIdx = trip.stops.indexOfFirst { it.id == nextId }
    val lastShown = if (driving && nextIdx >= 0) nextIdx + 1 else Int.MAX_VALUE
    val more = if (lastShown < Int.MAX_VALUE) (trip.stops.size - 1 - lastShown).coerceAtLeast(0) else 0
    trip.stops.forEachIndexed { i, s ->
        if (i < hidden || i > lastShown) return@forEachIndexed
        // A station before the next place (filling up right away) sits just above it, under the places already passed.
        if (fuelStop != null && fuelStop.afterStopId == null && s.id == nextId) CarEnter(Entry.FADE, key = fuelStop) { FuelStopRow(fuelStop, rail, showButton = detail, onFuelVia, ask?.takeIf { it.reason == PromptReason.FUEL }, onYes, onNo) }
        if (need != null && need.afterStopId == null && s.id == nextId) CarEnter(Entry.FADE, key = need) { FuelNeedRow(need, rail, showButton = detail, onFindFuel, ask?.takeIf { it.reason == PromptReason.FUEL }, onYes, onNo) }
        val isNext = s.id == nextId
        val color by carColor(when { s.visited -> Soft; isNext -> Accent; else -> Ink })
        val below = trip.stops.getOrNull(i + 1)
        val fill by animateFloatAsState(if (below?.visited == true) 1f else 0f, tween(450), label = "railFill")
        Row(Modifier.fillMaxWidth().carSize().height(IntrinsicSize.Min)) {
            Box(
                Modifier.width(rail).fillMaxHeight().drawBehind {
                    val x = size.width / 2
                    val cy = cyDp.toPx()
                    val w = (if (big) 4 else 3).dp.toPx()
                    val dots = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 7.dp.toPx()), 0f)
                    if (i > 0) drawLine(if (s.visited) railPassed else line, Offset(x, 0f), Offset(x, cy), w, StrokeCap.Round, if (s.visited) null else dots)
                    if (below != null) {
                        drawLine(line, Offset(x, cy), Offset(x, size.height), w, StrokeCap.Round, dots)
                        if (fill > 0f) drawLine(railPassed, Offset(x, cy), Offset(x, cy + (size.height - cy) * fill), w, StrokeCap.Round)
                    }
                },
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(Modifier.padding(top = (cyDp - node / 2)).size(node), contentAlignment = Alignment.Center) {
                    when {
                        s.visited -> Box(Modifier.size(small).clip(CircleShape).background(RailPassed), contentAlignment = Alignment.Center) { Text("✓", color = Color.White, fontSize = if (big) 14.sp else 11.sp, fontWeight = FontWeight.Bold) }
                        isNext -> Box(Modifier.size(node).clip(CircleShape).background(Accent))
                        else -> Box(Modifier.size(small).clip(CircleShape).background(Bg).border(if (big) 3.dp else 2.dp, NodeEdge, CircleShape))
                    }
                }
            }
            Column(Modifier.weight(1f).padding(bottom = if (below != null) gap else 0.dp)) {
                val shown = times[s.id] ?: s.time ?: estimated[s.id]
                Row(verticalAlignment = Alignment.Top) {
                    if (shown != null || detail) Column(Modifier.width(timeW)) {
                        shown?.let { Text(it, color = if (isNext) Accent else Soft, fontSize = if (big) 26.sp else 24.sp) }
                        // Under a moved time, what was planned; under a guessed one, that it is a guess (폰과 같은 말).
                        if (detail) when {
                            times[s.id] != null && s.time != null && times[s.id] != s.time -> Text("예정 ${s.time}", color = Soft, fontSize = 20.sp)
                            s.time == null && estimated[s.id] != null && times[s.id] == null -> Text("예상", color = Soft, fontSize = 20.sp)
                        }
                    }
                    Text(s.place.name, color = color, fontSize = if (isNext) fsNext else fs, fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    if (s.skipped) Text("  건너뜀", color = Soft, fontSize = 20.sp)
                    if (isNext && !big) Text("  다음", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                // The start question sits on the place it is about (팀장 2026-10-31), not in a band on top.
                if (ask != null && ask.reason in RAIL_ASK_START && ask.stop.id == s.id) RailAsk(ask, Modifier.padding(start = timeW, top = 10.dp), animate = !driving, onYes, onNo)
                if (s.memo.isNotBlank() && (isNext || !big || detail)) {
                    val (tag, rest) = com.htb.carplan.shared.calendar.StopMemo.split(s.memo)
                    Row(Modifier.padding(start = if (detail || s.time != null) timeW else 0.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        tag?.let { Text(it, color = Soft, fontSize = 20.sp, modifier = Modifier.border(1.dp, Line, Corner).padding(horizontal = 6.dp, vertical = 1.dp)) }
                        if (rest.isNotBlank()) Text(rest, color = Soft, fontSize = if (detail) 24.sp else 20.sp, maxLines = 1, modifier = Modifier.padding(start = if (tag != null) 8.dp else 0.dp))
                    }
                }
                // 12장 [차로 보내기]: the parking lot a passenger picked — where the car actually drives for this place.
                s.parkAt?.takeIf { !s.visited && (isNext || !big || detail) }?.let { lot ->
                    Row(Modifier.padding(start = if (detail || s.time != null) timeW else 0.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("주차", color = Accent, fontSize = 20.sp, modifier = Modifier.border(1.dp, Accent, Corner).padding(horizontal = 6.dp, vertical = 1.dp))
                        Text(lot.name, color = Accent, fontSize = if (detail) 24.sp else 20.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                // What the group said, as speech bubbles under the place — to read, not to answer (남기기는 폰에서).
                if (detail) comments[s.id].orEmpty().forEach { c ->
                    Column(Modifier.padding(start = timeW, top = 8.dp).border(1.dp, Line, Corner).padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Text(c.text, color = Ink, fontSize = 24.sp, maxLines = 3)
                        Text(listOfNotNull(c.who, c.at).joinToString(" · "), color = Soft, fontSize = 20.sp)
                    }
                }
                if (detail && below != null) legs[s.id]?.let { (m, sec) ->
                    Text("이동 약 ${(sec / 60).coerceAtLeast(1)}분 · ${"%.1f".format(m / 1000)}km", color = Soft, fontSize = 20.sp, modifier = Modifier.padding(start = timeW, top = 10.dp))
                }
                // Q55: the station to fill up at, right after the place it comes after (팀장 2026-10-31).
                if (fuelStop != null && fuelStop.afterStopId == s.id) CarEnter(Entry.FADE, key = fuelStop) { FuelStopRow(fuelStop, timeW, showButton = detail, onFuelVia, ask?.takeIf { it.reason == PromptReason.FUEL }, onYes, onNo) }
                if (need != null && need.afterStopId == s.id && below != null) CarEnter(Entry.FADE, key = need) { FuelNeedRow(need, timeW, showButton = detail, onFindFuel, ask?.takeIf { it.reason == PromptReason.FUEL }, onYes, onNo) }
            }
        }
    }
    if (more > 0) Text("그 뒤 ${more}곳 더", color = Soft, fontSize = 20.sp, modifier = Modifier.padding(start = rail, top = 6.dp))
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
            contentColor = if (primary) OnInk else Ink,
            disabledContainerColor = if (primary) Disabled else Bg,
            disabledContentColor = Soft,
        ),
    ) { Text(label, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}

/**
 * 7장 3: the invite QR for today's group. Passengers scan it with the phone app (or any camera:
 * the link opens the Hosting page, which opens the app or explains how to install it).
 * No trip today → [새 그룹] makes one on the spot (Q16).
 */
@Composable
private fun InviteQrDialog(group: Group?, seated: Boolean, tripTitle: String?, friendCode: String?, ownerName: String, onShareTrip: () -> Unit, onDismiss: () -> Unit) {
    // No trip today: the QR adds the owner as a friend instead of making an empty group (팀장 2026-10-31).
    val friendQr = group == null && tripTitle == null && friendCode != null
    CarDialog(
        onDismiss = onDismiss,
        title = when {
            friendQr -> "${ownerName}님과 친구 맺기"
            seated -> "같이 타셨나요? QR 찍고 들어오세요"
            group == null -> "같이 탄 사람 추가"
            else -> "${group.name}에 같이 가기"
        },
        buttons = {
            // Car-sized buttons: the main one filled black, the other outlined (디자인 규칙).
            val closeBtn: @Composable () -> Unit = {
                CarCloseButton(onClick = onDismiss)
            }
            if (group == null && tripTitle != null) {
                closeBtn()
                Button(onClick = onShareTrip, shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = OnInk), modifier = Modifier.height(76.dp)) { Text("이 여행에 초대하기", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
            } else closeBtn()
        },
    ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (friendQr) {
                    val link = InviteLinks.url(friendCode!!)
                    val bitmap = rememberQr(link, 560)
                    if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = "친구 QR", modifier = Modifier.size(280.dp)) else Spacer(Modifier.size(280.dp))
                    Text("폰으로 찍으면 ${ownerName}님과 친구가 돼요", fontSize = 24.sp, color = Ink)
                    Text("다음 일정부터는 이름만 눌러 초대해요 · 코드 $friendCode", fontSize = 20.sp, color = Soft)
                } else if (group == null) {
                    if (tripTitle != null) {
                        Text("“$tripTitle”에 같이 갈 사람을 초대해요.", fontSize = 26.sp, color = Ink, modifier = Modifier.fillMaxWidth())
                        Text("QR이 뜨면 폰으로 찍거나, 링크·코드로 들어와요. 칸·코멘트도 같이 보고 고칠 수 있어요.", fontSize = 20.sp, color = Soft, modifier = Modifier.fillMaxWidth())
                    } else {
                        Text("오늘 걸린 여행이 없어요.", fontSize = 26.sp, color = Ink, modifier = Modifier.fillMaxWidth())
                        Text("새 그룹을 만들면 QR이 떠요. 같이 탄 사람이 폰으로 찍으면 들어와요.", fontSize = 20.sp, color = Soft, modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    val link = InviteLinks.url(group.inviteCode)
                    val bitmap = rememberQr(link, 560)
                    if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = "초대 QR", modifier = Modifier.size(280.dp)) else Spacer(Modifier.size(280.dp))
                    Text("폰으로 QR을 찍으면 바로 들어와요", fontSize = 20.sp, color = Ink)
                    // Same invite without a camera: the link (carplan-htb.web.app/i/CODE) or the code typed in the app.
                    Text("링크  ${link.removePrefix("https://")}", fontSize = 20.sp, color = Ink)
                    Text("코드  ${group.inviteCode}   ·   지금 ${group.memberIds.size}명", fontSize = 20.sp, color = Soft)
                }
            }
    }
}

/** 주유소·충전소·주차장·맛집 near the car, in our own window: one tap = next waypoint, carplan stays on screen. */
@Composable
private fun NearbyDialog(list: NearbyList, onPick: (NearPlace) -> Unit, onDismiss: () -> Unit) {
    CarDialog(onDismiss = onDismiss, title = list.title, buttons = { CarCloseButton(onClick = onDismiss) }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            list.items.forEach { p ->
                val dist = if (p.meters >= 1000) "%.1fkm".format(p.meters / 1000.0) else "${p.meters}m"
                Button(onClick = { onPick(p) }, modifier = Modifier.fillMaxWidth().height(if (p.where != null) 96.dp else 76.dp).border(1.dp, Line, Corner), shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Bg, contentColor = Ink)) {
                    // Name first, always (2026-11-05: a long "○○ 근처" squeezed the station name out); where on the day under it.
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        p.where?.let { Text("$dist · $it", fontSize = 20.sp, color = Soft, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                    }
                    p.priceWon?.let { Text("  ${"%,d".format(it)}원", fontSize = 26.sp, color = Ink, fontWeight = FontWeight.Bold) }
                    if (p.where == null) Text("  $dist", fontSize = 24.sp, color = Soft, maxLines = 1)
                }
            }
            Text(list.note, fontSize = 20.sp, color = Soft)
        }
    }
}

/** 12장: the three cheapest stations near the car; one tap makes it the next waypoint. */
@Composable
private fun StationDialog(stations: List<GasStation>, onPick: (GasStation) -> Unit, onDismiss: () -> Unit) {
    CarDialog(onDismiss = onDismiss, title = "가까운 최저가 주유소", buttons = { CarCloseButton(onClick = onDismiss) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                stations.forEach { st ->
                    Button(onClick = { onPick(st) }, modifier = Modifier.fillMaxWidth().height(76.dp).border(1.dp, Line, Corner), shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Bg, contentColor = Ink)) {
                        Text(st.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("${"%,d".format(st.price)}원", fontSize = 26.sp, color = Ink, fontWeight = FontWeight.Bold)
                        Text("  ${if (st.meters >= 1000) "%.1fkm".format(st.meters / 1000.0) else "${st.meters}m"}", fontSize = 20.sp, color = Soft)
                    }
                }
                Text("고르면 다음 경유지로 들어가고, 그다음 원래 일정으로 이어져요", fontSize = 20.sp, color = Soft)
            }
    }
}

/**
 * ⑧ [말하기]: "듣는 중" while the mic is open; when nothing was understood, the commands it knows as big buttons
 * for a few seconds (Q21: the buttons are the fallback for voice).
 */
@Composable
private fun VoiceBar(state: VoiceState, onCommand: (VoiceCommand) -> Unit, onClose: () -> Unit) {
    if (!state.listening && !state.choices) return
    Column(Modifier.fillMaxWidth().background(Band).padding(horizontal = 32.dp, vertical = 14.dp)) {
        if (state.listening) {
            Text("듣는 중 · \"주유소\" \"주차장\" \"다음 어디야\" \"건너뛰기\" \"원래대로\"", color = OnBand, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.heard?.takeIf { it.isNotBlank() }?.let { "들은 말 \"$it\" · 골라 주세요" } ?: "골라 주세요", color = BandSoft, fontSize = 20.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("닫기", color = BandSoft, fontSize = 20.sp) }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(VoiceCommand.FUEL to "주유소", VoiceCommand.PARKING to "주차장", VoiceCommand.WHERE to "다음 어디", VoiceCommand.SKIP to "건너뛰기", VoiceCommand.REVERT to "원래대로").forEach { (c, label) ->
                    Button(onClick = { onCommand(c) }, modifier = Modifier.weight(1f).height(76.dp), shape = Corner,
                        colors = ButtonDefaults.buttonColors(containerColor = OnBand, contentColor = Color(0xFF111111))) { Text(label, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                }
            }
        }
    }
}

/** 시뮬레이션이 도는 동안 맨 위 한 줄: 지금 무슨 장면인지 + 일시정지·배속·끝. */
@Composable
private fun SimBar(sim: SimState, onPause: () -> Unit, onSpeed: () -> Unit, onStop: () -> Unit, onRemoveStop: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("시뮬레이션", color = Soft, fontSize = 20.sp)
        Text(sim.phase, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        sim.remainMeters?.let { Text(if (it >= 1000) "%.1f km".format(it / 1000.0) else "$it m", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
        if (!sim.viaConsole) Text("콘솔 없이", color = Soft, fontSize = 20.sp)
        Spacer(Modifier.weight(1f))
        // 10장 check while driving (the menu is closed in D): a passenger takes a stop out on their phone.
        OutlinedButton(onClick = onRemoveStop, shape = Corner, border = androidx.compose.foundation.BorderStroke(1.dp, Line)) { Text("칸 하나 빼기", color = Ink, fontSize = 20.sp) }
        OutlinedButton(onClick = onPause, shape = Corner, border = androidx.compose.foundation.BorderStroke(1.dp, Line)) { Text(if (sim.paused) "계속" else "일시정지", color = Ink, fontSize = 20.sp) }
        OutlinedButton(onClick = onSpeed, shape = Corner, border = androidx.compose.foundation.BorderStroke(1.dp, Line)) { Text(if (sim.speedX == 1) "2배속" else "1배속", color = Ink, fontSize = 20.sp) }
        TextButton(onClick = onStop) { Text("끝", color = Soft, fontSize = 20.sp) }
    }
    HorizontalDivider(color = Line)
}

/**
 * 시연 메뉴 (팀장 2026-11-11: 차 없이 컴으로 시연 영상을 찍으니, 화면에서 직접 할 수 없는 것 — 차가 하는 일과 다른
 * 사람이 하는 일 — 만). Laid out as the video's scenes, top to bottom; 일정 추가·일기 쓰기 are done on screen.
 */
@Composable
private fun DebugDialog(v: CarState, vm: CarHomeViewModel, onDismiss: () -> Unit) {
    // Watched, not read once: the buttons follow the trip and guiding while the menu is open (lint, QA 2026-11-01).
    val guiding by vm.guiding.collectAsStateWithLifecycle()
    val trip by vm.trip.collectAsStateWithLifecycle()
    val demoTrip = trip?.title == CarHomeViewModel.DEMO_TRIP_TITLE
    CarDialog(onDismiss = onDismiss, title = "시연 메뉴", width = 1100.dp, buttons = { CarCloseButton(onClick = onDismiss) }) {
        Column(Modifier.heightIn(max = 780.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("촬영 순서대로 위에서부터 눌러요. 일정 추가·일기 쓰기는 화면에서 직접 해요.", fontSize = 20.sp, color = Soft)
            DemoScene("0", "촬영 준비", "지난 여행·기록·친구가 있는 11월로 다시 채워요") {
                DemoButton("11월 다시 채우기") { vm.refillNovember(); onDismiss() }
            }
            DemoScene("1", "오늘 여행", "주말 친구들과 분당 4곳 · 다시 누르면 처음 상태로") {
                DemoButton(if (demoTrip) "처음으로 돌리기" else "오늘 여행 만들기", primary = !demoTrip) { vm.makeTestTrip() }
            }
            DemoScene("2", "일행 탑승", "조수석·뒷좌석에 앉음 → \"같이 타셨나요?\" QR") {
                DemoButton("일행 탑승") { vm.boardPassengers(); onDismiss() }
            }
            DemoScene("3", "주행", "D → 실제 도로로 이동 → 도착 → P → 문 열고 내림 (기어·문·좌석은 Virtual Console)") {
                DemoButton("주행 재생", primary = true) { vm.playSim(); onDismiss() }
                DemoButton("폰으로 보기 재생") { vm.playSimForPhone(); onDismiss() }
                DemoButton("멈춤") { vm.stopSim() }
                if (guiding) DemoButton("다음 칸 도착") { vm.debugArrive(); onDismiss() }
            }
            DemoScene("4", "친구가 일정 바꿈", "민지가 폰에서 다음 칸 앞에 카페를 넣음 → 차 경로가 바뀜") {
                DemoButton("민지가 칸 넣기") { vm.friendAddsStop(); onDismiss() }
            }
            DemoScene("5", "일행 반응", "서연·지훈이 가는 곳에 코멘트, 여행 채팅에 한 줄씩") {
                DemoButton("일행 코멘트·채팅") { vm.friendsTalk(); onDismiss() }
                DemoButton("지훈이 투표 열기") { vm.friendVote(); onDismiss() }
            }
            DemoScene("6", "데리러 가기", "지훈이 수내역에서 태워 달라고 함 → 차가 먼저 들름") {
                DemoButton("픽업 부탁") { vm.makeTestPickup(); onDismiss() }
            }
            DemoScene("7", "연료", "지금 ${v.fuelPercent?.toInt() ?: "—"}% · ${if (v.isEv == true) "전기차" else if (v.isEv == false) "연료차" else "종류 모름"} · 15%면 주유·충전 질문") {
                DemoButton("연료 15%", primary = true) { vm.setFuelOverride(15.0); onDismiss() }
                DemoButton("100%") { vm.setFuelOverride(100.0) }
                DemoButton("실제값") { vm.setFuelOverride(null) }
                DemoButton(if (v.isEv == true) "연료차로" else "전기차로") { vm.setEvOverride(v.isEv != true) }
            }
            DemoScene("8", "하루 끝", "주행 재생이 마지막 곳에서 P + 문으로 끝나면 기록 칸이 생겨요 → 폰 기록 탭") {}
            DemoScene("9", "아침 출근", "지금 시각 출근 일정 → \"회사로 갈까요?\"") {
                DemoButton("출근 상황 만들기") { vm.makeTestCommute(); onDismiss() }
                DemoButton("출근 지우기") { vm.removeTestCommute() }
            }
            DemoScene("10", "화면", "내비 옆 반반 · 밤 모드") {
                val split by vm.previewSplit.collectAsStateWithLifecycle()
                val dark by vm.previewDark.collectAsStateWithLifecycle()
                DemoButton(if (split) "반반 끄기" else "반반 보기", primary = split) { vm.previewSplit.value = !split }
                DemoButton(if (dark) "밤 모드 끄기" else "밤 모드", primary = dark) { vm.previewDark.value = !dark }
            }
            HorizontalDivider(color = Line, modifier = Modifier.padding(top = 12.dp))
            Text("준비 (촬영 전에만)", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.padding(top = 12.dp))
            // 다른 폰(새로 깐 앱의 새 계정)을 차주로 붙일 때: 연결을 풀면 차가 6자리 화면으로 돌아간다.
            // A phone account (its 친구 코드 from 설정) into all of the owner's shared groups — e.g. after reinstalling the phone app.
            var phoneCode by remember { mutableStateOf("") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), itemVerticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                DemoButton("차주 연결 풀기") { vm.unlinkOwner(); onDismiss() }
                DemoButton("친구 계정 고정") { vm.pinFriends(); onDismiss() }
                androidx.compose.material3.OutlinedTextField(phoneCode, { phoneCode = it.uppercase().take(8) }, singleLine = true, modifier = Modifier.width(220.dp),
                    placeholder = { Text("폰 친구 코드", fontSize = 20.sp) }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 22.sp, color = Ink))
                DemoButton("폰 계정 넣기") { if (phoneCode.length >= 4) { vm.bringInPhone(phoneCode); onDismiss() } }
            }
        }
    }
}

/** One scene of the 시연 영상: its number, what it shows, and the buttons for it in a row. */
@Composable
private fun DemoScene(no: String, title: String, hint: String, buttons: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(no, fontSize = 24.sp, fontWeight = FontWeight.Black, color = Ink, modifier = Modifier.width(44.dp))
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
        }
        Text(hint, fontSize = 20.sp, color = Soft, modifier = Modifier.padding(start = 44.dp, top = 2.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(start = 44.dp, top = 10.dp)) { buttons() }
    }
}

/** A 시연 메뉴 button, 76dp like every car button: black when it is the scene's main action, else white with an edge. */
@Composable
private fun DemoButton(label: String, primary: Boolean = false, onClick: () -> Unit) {
    if (primary) Button(onClick = onClick, shape = Corner, modifier = Modifier.heightIn(min = 76.dp), colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = OnInk)) {
        Text(label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp))
    } else OutlinedButton(onClick = onClick, shape = Corner, modifier = Modifier.heightIn(min = 76.dp), border = BorderStroke(1.dp, SheetEdge), colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)) {
        Text(label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

/** zxing core → black-on-white bitmap, filled in one go (setPixel per pixel stalled the tab switch) and kept. */
internal fun qrBitmap(text: String, size: Int): Bitmap = qrCache.getOrPut("$size@$text") {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (matrix[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

private val qrCache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

/** The QR made off the main thread: null for the first frame or two, then the code (no stall when a screen opens). */
@Composable
internal fun rememberQr(text: String, size: Int): Bitmap? =
    androidx.compose.runtime.produceState(qrCache["$size@$text"], text, size) {
        if (value == null) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { qrBitmap(text, size) }
    }.value

/** 차에서 일정 만들기 (Q19): full screen, big fields, time on a 시·분 wheel, never closed by a stray tap. */
@Composable
private fun CarAddDialog(vm: CarHomeViewModel, date: LocalDate = LocalDate.now(), onSave: (Event) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    // Opened from the calendar on a later day: that day is already chosen.
    var dayOffset by remember { mutableStateOf(java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), date)) }
    var datePick by remember { mutableStateOf(false) }
    var timed by remember { mutableStateOf(false) }
    var hm by remember { mutableStateOf((java.time.LocalTime.now().hour + 1) % 24 to 0) }
    var place by remember { mutableStateOf("") }
    // The place is matched right away (현대 내비 먼저, 카카오는 보조 → 좌표), so the navigation gets the exact spot, not a name to guess.
    var picked by remember { mutableStateOf<Place?>(null) }
    // A안: not sure where yet → throw the dart here; the place it picks fills the place (and an empty title).
    var darting by remember { mutableStateOf(false) }
    val time = if (timed) "%02d:%02d".format(hm.first, hm.second) else null
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    // Keyboard [완료] / Enter: put the keyboard away (the hardware keyboard types straight into the field).
    val done = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        // The car keyboard (CarIME) ignores Compose's hide(): ask InputMethodManager directly.
        val view = androidx.compose.ui.platform.LocalView.current
        val hideKeyboard = remember(view) { { view.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0); Unit } }
        val doneAction = androidx.compose.foundation.text.KeyboardActions(onDone = { hideKeyboard(); focus.clearFocus() })
        CarEnter(Entry.SCREEN) {
        Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 40.dp, vertical = 24.dp)) {
            // The keyboard shrinks this window: the fields scroll instead of squashing (the place field used to become a line, text unseen).
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CarBackButton(onBack = onDismiss)
                Text("일정 추가", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.padding(start = 16.dp))
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                title, { title = it }, placeholder = { Text("무슨 일정이에요?", fontSize = 28.sp, color = Soft) }, singleLine = true, shape = Corner, keyboardOptions = done, keyboardActions = doneAction,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 28.sp, color = Ink), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            Text("날짜", color = Soft, fontSize = 20.sp)
            // One button: the chosen day; it opens the month calendar.
            FilterChip(selected = true, onClick = { datePick = true }, shape = Corner, colors = chipColors(), modifier = Modifier.padding(top = 6.dp), label = { Text(dayTitle(LocalDate.now().plusDays(dayOffset)), fontSize = 26.sp, modifier = Modifier.padding(10.dp)) })
            Text("몇 시", color = Soft, fontSize = 20.sp, modifier = Modifier.padding(top = 14.dp))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = !timed, onClick = { timed = false }, shape = Corner, colors = chipColors(), label = { Text("종일", fontSize = 26.sp, modifier = Modifier.padding(10.dp)) })
                FilterChip(selected = timed, onClick = { timed = true }, shape = Corner, colors = chipColors(), label = { Text(time ?: "시간 정하기", fontSize = 26.sp, modifier = Modifier.padding(10.dp)) })
            }
            // 시간 정하기 → 시·분을 굴려서 고름 (hour buttons were too many to scan).
            CarAppear(timed) { CarTimeWheel(hm.first, hm.second, { h, m -> hm = h to m }, Modifier.padding(top = 10.dp).widthIn(max = 520.dp)) }
            Spacer(Modifier.height(18.dp))
            CarPlacePicker(vm, place, { place = it; picked = null }, picked, onPick = { picked = it; place = it.name })
            OutlinedButton(
                onClick = { darting = true }, shape = Corner, border = BorderStroke(1.dp, SheetEdge),
                modifier = Modifier.padding(top = 12.dp).heightIn(min = 76.dp),
            ) { Text("어디 갈지 모르겠으면  다트로 정하기", fontSize = 24.sp, color = Ink, fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                enabled = title.isNotBlank(), onClick = {
                    val date = LocalDate.now().plusDays(dayOffset).toString()
                    onSave(Event(title = title.trim(), startDate = date, endDate = date, allDay = time == null, startTime = time, place = picked ?: place.trim().takeIf { it.isNotEmpty() }?.let { Place(name = it) }))
                },
                modifier = Modifier.fillMaxWidth().height(96.dp), shape = Corner,
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = OnInk, disabledContainerColor = Disabled, disabledContentColor = Soft),
            ) { Text(listOfNotNull(dayLabel(LocalDate.now().plusDays(dayOffset)), time ?: "종일", title.ifBlank { null }).joinToString("  ") + "  추가", fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
        }
    }
    }
    if (darting) CarDartGame(vm, onDismiss = { darting = false }, onPick = { p ->
        darting = false; picked = p; place = p.name
        if (title.isBlank()) title = p.name
    }, onCourse = { town, places ->
        // 하루 코스 통째로: 고른 날짜에 여행 하나 + 코스 칸들 (팀장 2026-11-12).
        vm.addCourseTrip(LocalDate.now().plusDays(dayOffset), title.trim().ifBlank { "$town 하루" }, town, places)
        darting = false; onDismiss()
    })
    if (datePick) CarDatePickDialog(LocalDate.now().plusDays(dayOffset), onPick = { dayOffset = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), it); datePick = false }, onDismiss = { datePick = false })
}

/** 날짜 고르기: one month, ‹ › or a side swipe to change it, tap a day to choose. Days before today can't be chosen. */
@Composable
private fun CarDatePickDialog(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val c = LocalCarColors.current
    val today = LocalDate.now()
    var month by remember { mutableStateOf(java.time.YearMonth.from(initial)) }
    CarDialog(onDismiss = onDismiss, title = "날짜 고르기", width = 760.dp, buttons = { CarCloseButton(onClick = onDismiss) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val canBack = month > java.time.YearMonth.from(today)
            OutlinedButton(onClick = { month = month.minusMonths(1) }, enabled = canBack, shape = Corner, modifier = Modifier.heightIn(min = 76.dp).widthIn(min = 76.dp), border = BorderStroke(1.dp, c.line)) {
                Text("‹", color = if (canBack) c.ink else c.railPassed, fontSize = 28.sp)
            }
            Text("${month.year}년 ${month.monthValue}월", color = c.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { month = month.plusMonths(1) }, shape = Corner, modifier = Modifier.heightIn(min = 76.dp).widthIn(min = 76.dp), border = BorderStroke(1.dp, c.line)) {
                Text("›", color = c.ink, fontSize = 28.sp)
            }
        }
        Row(Modifier.padding(top = 12.dp)) {
            listOf("일", "월", "화", "수", "목", "금", "토").forEach { d -> Text(d, color = c.soft, fontSize = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        val lead = month.atDay(1).dayOfWeek.value % 7 // Sunday first
        val days = month.lengthOfMonth()
        Column(Modifier.padding(top = 6.dp).monthSwipe(month, onPrev = { if (month > java.time.YearMonth.from(today)) month = month.minusMonths(1) }, onNext = { month = month.plusMonths(1) })) {
            for (r in 0 until (lead + days + 6) / 7) Row {
                for (col in 0 until 7) {
                    val n = r * 7 + col - lead + 1
                    Box(Modifier.weight(1f).height(76.dp).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (n in 1..days) {
                            val d = month.atDay(n)
                            val past = d < today
                            val on = d == initial
                            Box(
                                Modifier.fillMaxSize().background(if (on) c.ink else c.bg, Corner)
                                    .then(if (d == today && !on) Modifier.border(2.dp, c.ink, Corner) else Modifier)
                                    .then(if (past) Modifier else Modifier.clickable { onPick(d) }),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("$n", fontSize = 26.sp, fontWeight = if (on || d == today) FontWeight.Bold else FontWeight.Normal,
                                    color = when { on -> c.onInk; past -> c.railPassed; col == 0 -> c.warn; else -> c.ink })
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun dayTitle(date: LocalDate) =
    "${date.monthValue}월 ${date.dayOfMonth}일 (${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})"

/** "오늘" / "내일" / "모레" / "11월 3일" */
private fun dayLabel(d: LocalDate): String = when (java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), d)) {
    0L -> "오늘"; 1L -> "내일"; 2L -> "모레"; else -> "${d.monthValue}월 ${d.dayOfMonth}일"
}
