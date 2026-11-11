package com.htb.carplan.phone.trip

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import com.htb.carplan.phone.record.DayExport
import com.htb.carplan.phone.record.DayVideo
import com.htb.carplan.phone.record.ExportItem
import com.htb.carplan.phone.record.Photos
import com.htb.carplan.phone.ui.CenterSheet
import com.htb.carplan.phone.vote.DartSheet
import com.htb.carplan.phone.vote.VoteSheet
import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.model.Record
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.NodeState
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.phone.ui.sideSwipe
import com.htb.carplan.phone.ui.RailNode
import com.htb.carplan.phone.ui.MapMarker
import com.htb.carplan.phone.ui.SimpleMap
import com.htb.carplan.phone.ui.railLine
import com.htb.carplan.phone.ui.speechBubble
import com.htb.carplan.shared.calendar.RouteMath.hasCoords
import com.htb.carplan.shared.model.Comment
import com.htb.carplan.shared.model.DayStatus
import com.htb.carplan.shared.model.Stop
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private val RAIL_W = 48.dp
private val NODE = 26.dp

/**
 * 3장 그날 장소 칸 as one living timeline (the sketch docs/캘린더예시/타임라인_스케치.png): a rail on the left with a node
 * per stop, a box with time + place, comment bubbles hanging under each stop, the drive between
 * stops on a dotted rail, and a "+" node at the end. The same rail is what the car fills in later
 * (visited nodes grey, the next one blue). Everything saves immediately.
 */
/** The day's VM for small leaf rows (또 갈래요) without passing it through every row. */
private val LocalTripVm = androidx.compose.runtime.staticCompositionLocalOf<TripDayViewModel?> { null }

@Composable
fun TripDayScreen(start: TripRef, onBack: () -> Unit, parkingStopId: String? = null) {
    var ref0 by remember(start) { mutableStateOf(start) }
    val vm0: TripDayViewModel = viewModel(key = ref0.groupId + ref0.eventId, factory = tripVmFactory(ref0))
    // A full screen slides in from the right (same as the car).
    com.htb.carplan.phone.ui.Enter(com.htb.carplan.phone.ui.Entry.SCREEN) {
        androidx.compose.runtime.CompositionLocalProvider(LocalTripVm provides vm0) { TripDayBody(ref0, vm0, onRefChange = { ref0 = it }, onBack = onBack, parkingStopId = parkingStopId) }
    }
}

@Composable
private fun TripDayBody(ref: TripRef, vm: TripDayViewModel, onRefChange: (TripRef) -> Unit, onBack: () -> Unit, parkingStopId: String? = null) {
    // The first invite moves the event to its own group (A안); the screen follows it there.
    LaunchedEffect(vm) { vm.movedTo.collect { gid -> if (gid != null) onRefChange(ref.copy(groupId = gid, date = vm.date.value)) } }
    var inviteOpen by rememberSaveable { mutableStateOf(false) }
    val event by vm.event.collectAsStateWithLifecycle()
    val dates by vm.dates.collectAsStateWithLifecycle()
    val date by vm.date.collectAsStateWithLifecycle()
    val stops by vm.stops.collectAsStateWithLifecycle()
    val removed by vm.removed.collectAsStateWithLifecycle()
    val legs by vm.legs.collectAsStateWithLifecycle()
    val comments by vm.comments.collectAsStateWithLifecycle()
    val estimates by vm.estimates.collectAsStateWithLifecycle()
    val day by vm.day.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val me by vm.uid.collectAsStateWithLifecycle()
    val locked by vm.editLocked.collectAsStateWithLifecycle()
    val group by vm.group.collectAsStateWithLifecycle()
    val myCarId by vm.myCarId.collectAsStateWithLifecycle()
    vm.names.collectAsStateWithLifecycle().value // recompose when a name arrives

    val records by vm.records.collectAsStateWithLifecycle()
    val recordByStop by vm.recordByStop.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Stop?>(null) }
    var adding by remember { mutableStateOf(false) }
    var timeFor by remember { mutableStateOf<Stop?>(null) }
    var photoFor by remember { mutableStateOf<String?>(null) }
    var noteFor by remember { mutableStateOf<Record?>(null) }
    var placeFor by remember { mutableStateOf<Record?>(null) }
    var parkingFor by remember { mutableStateOf<Record?>(null) }
    var manualRecord by remember { mutableStateOf(false) }
    var voteOpen by remember { mutableStateOf(false) }
    var voteSeed by remember { mutableStateOf<com.htb.carplan.shared.model.Place?>(null) }
    var pickupOpen by remember { mutableStateOf(false) }
    val pickups by vm.pickups.collectAsStateWithLifecycle()
    var aiOpen by remember { mutableStateOf(false) }
    var dartOpen by remember { mutableStateOf(false) }
    // 여행별 채팅방 (A안 2026-11-03): only once someone else is on the trip.
    var chatOpen by remember { mutableStateOf(false) }
    val chatLines by remember(ref.groupId) { com.htb.carplan.shared.data.ChatRepository().observe(ref.groupId) }
        .collectAsStateWithLifecycle(emptyList())
    var chatSeen by remember(ref.groupId) { mutableStateOf(0L) }
    val sharedTrip = (group?.memberIds?.size ?: 0) > 1
    var tripInfoOpen by remember { mutableStateOf(false) }
    var chatHint by remember { mutableStateOf(false) }
    if (chatHint) com.htb.carplan.phone.ui.CenterSheet(onDismiss = { chatHint = false }) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("여행 채팅", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
            Text("같이 갈 사람을 초대하면 이 여행의 채팅방이 생겨요. 장소·투표·다트를 채팅에서 바로 나누고, 칸이 바뀌면 채팅에 알려 줘요.",
                fontSize = 16.sp, color = Palette.TextSoft, lineHeight = 23.sp, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp))
            Button(onClick = { chatHint = false; inviteOpen = true }, shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("같이 갈 사람 초대하기", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = { chatHint = false }, modifier = Modifier.align(Alignment.End)) { Text("닫기", color = Palette.TextSoft) }
        }
    }
    var nearbyFor by remember { mutableStateOf<com.htb.carplan.shared.model.Stop?>(null) }
    nearbyFor?.let { s -> NearbySheet(s, vm, canAdd = !locked, onDismiss = { nearbyFor = null }) }
    if (tripInfoOpen) event?.let { ev ->
        com.htb.carplan.phone.calendar.EventEditSheet(
            item = com.htb.carplan.shared.data.CalendarEvent(ref.groupId, ev), categories = emptyList(), onAddCategory = { _, _, _ -> },
            onSave = { t, c, st, en, ad, tm, pl -> tripInfoOpen = false; vm.updateEventInfo(t, c, st, en, ad, tm, pl) },
            onMakeTrip = {}, onDelete = { tripInfoOpen = false; vm.deleteTrip(); onBack() }, onDismiss = { tripInfoOpen = false },
        )
    }
    // 12장 주차장 찾기: opened by the push (once), or by the [주차장] chip.
    var parkingAround by rememberSaveable { mutableStateOf<String?>(null) }
    var parkingShown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(parkingStopId, stops.isNotEmpty()) { if (parkingStopId != null && !parkingShown && stops.isNotEmpty()) { parkingShown = true; parkingAround = parkingStopId } }
    parkingAround?.let { sid ->
        val target = stops.firstOrNull { it.id == sid }?.place
        if (target != null) ParkingSheet(target, ref.groupId, ref.eventId, date, me, shareToChat = sharedTrip, onDismiss = { parkingAround = null })
    }
    // 내보내기 메뉴 — the chat's [하루 카드 만들기] opens it too.
    var exportMenu by remember { mutableStateOf(false) }
    var placeQuery by remember { mutableStateOf("") }
    var voteSeeds by remember { mutableStateOf<List<Place>>(emptyList()) }
    var placePicker by remember { mutableStateOf<((Place) -> Unit)?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 14장: pick a photo → shrink to 1600 px → upload to the record (Q40).
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = photoFor
        photoFor = null
        if (uri != null && target != null) scope.launch(Dispatchers.IO) {
            Photos.downscaleToJpeg(context, uri)?.let { vm.addPhoto(target, it) }
        }
    }
    // Unplanned records (휴게소 etc.) sit after the last planned stop the car had reached before them.
    val unplanned = records.filter { it.stopId == null }
    fun unplannedAfter(index: Int): List<Record> {
        val at = stops.getOrNull(index)?.let { recordByStop[it.id]?.arrivedAt?.toDate()?.time } ?: return if (index == stops.lastIndex) unplanned.filter { u -> stops.none { s -> (recordByStop[s.id]?.arrivedAt?.toDate()?.time ?: Long.MAX_VALUE) <= (u.arrivedAt?.toDate()?.time ?: 0L) } } else emptyList()
        val nextAt = stops.drop(index + 1).firstNotNullOfOrNull { recordByStop[it.id]?.arrivedAt?.toDate()?.time } ?: Long.MAX_VALUE
        return unplanned.filter { u -> val t = u.arrivedAt?.toDate()?.time ?: 0L; t >= at && t < nextAt }
    }

    val driving = day?.status == DayStatus.DRIVING
    val nextId = if (driving) stops.firstOrNull { !it.visited }?.id else null

    Column(Modifier.fillMaxSize().background(Palette.Background).statusBarsPadding()) {
        // Header (디자인 평가 2026-11-04: on a phone the title, who-line and car button shared one row and wrapped into
        // a tall jumble): line 1 = back · title · ⋮, line 2 = who's coming · 같이 갈 사람, line 3 = the car, if any.
        val members = group?.takeUnless { it.solo }?.memberIds.orEmpty()
        val who = if (members.size <= 1) "나만" else (listOfNotNull(me) + members.filter { it != me }).take(3).joinToString(" · ") { vm.nameOf(it) } + if (members.size > 3) " 외 ${members.size - 3}명" else ""
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = Palette.TextStrong) }
            Text(event?.title?.ifBlank { null } ?: "여행", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (busy) Text("저장 중", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(end = 12.dp))
            // 14장 내보내기: 이미지 1장(Q39) 또는 짧은 영상(사진 슬라이드 + 장소·시간 자막)
            var exporting by remember { mutableStateOf<String?>(null) }
            fun exportItems() = buildList {
                stops.forEachIndexed { i, s ->
                    val r = recordByStop[s.id]
                    add(ExportItem(r?.arrivedAt?.let { hhmm(it) } ?: s.time, s.place.name, r?.note?.ifBlank { null } ?: s.memo, r?.photoUrls?.firstOrNull(), false, r?.photoUrls.orEmpty()))
                    unplannedAfter(i).forEach { u -> add(ExportItem(u.arrivedAt?.let { hhmm(it) }, u.place.name, u.note, u.photoUrls.firstOrNull(), true, u.photoUrls)) }
                }
                if (stops.isEmpty()) unplanned.forEach { u -> add(ExportItem(u.arrivedAt?.let { hhmm(it) }, u.place.name, u.note, u.photoUrls.firstOrNull(), true, u.photoUrls)) }
            }
            // 내보내기 behind one menu; 픽업·투표·다트 sit in their own row under the header (P-c).
            Box {
                IconButton(onClick = { exportMenu = true }, enabled = exporting == null) { Icon(Icons.Filled.MoreVert, "더보기", tint = Palette.TextStrong) }
                exporting?.let { Text(it, fontSize = 13.sp, color = Palette.TextSoft, modifier = Modifier.align(Alignment.BottomCenter)) }
                androidx.compose.material3.DropdownMenu(exportMenu, onDismissRequest = { exportMenu = false }) {
                    // 여행 정보 고치기 (팀장 2026-11-04): 제목·날짜.
                    androidx.compose.material3.DropdownMenuItem({ Text("여행 정보 고치기") }, onClick = { exportMenu = false; tripInfoOpen = true })
                    if (records.isEmpty()) androidx.compose.material3.DropdownMenuItem({ Text("내보낼 기록이 아직 없어요", color = Palette.TextSoft) }, onClick = { exportMenu = false })
                    if (records.isNotEmpty()) androidx.compose.material3.DropdownMenuItem({ Text("내보내기 · 이미지 1장") }, onClick = {
                        exportMenu = false
                        scope.launch { runCatching { DayExport.share(context, event?.title ?: "하루 기록", "${date}  ·  ${group?.name ?: ""}", exportItems()) } }
                    })
                    if (records.isNotEmpty()) androidx.compose.material3.DropdownMenuItem({ Text("내보내기 · 짧은 영상") }, onClick = {
                        exportMenu = false
                        scope.launch {
                            exporting = "영상 준비 중"
                            runCatching { DayVideo.share(context, event?.title ?: "하루 기록", "${date}  ·  ${group?.name ?: ""}", exportItems()) { exporting = it } }
                                .onFailure { vm.setError("영상 만들기 실패: ${it.message}") }
                            exporting = null
                        }
                    })
                }
            }
        }

        // Who's coming (초대 = 일정) and the car, under the title, each on one line.
        Row(Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f, fill = false).clip(Corner).clickable { inviteOpen = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(listOfNotNull(who, dayLine(day)).joinToString(" · "), fontSize = 15.sp, color = if (day?.holdByDriver == true) Palette.Sunday else Palette.TextSoft,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text("  같이 갈 사람", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Navy, maxLines = 1)
            }
        }
        // 7장 2: "오늘 이 차로 가요" — the trip gets my car and me as driver; the car app then shows it.
        val e = event
        when {
            e?.carId != null -> Text(if (e.driverUid == me) "내 차로 가요 · 운전자 나" else "차 정해짐 · 운전자 ${vm.nameOf(e.driverUid)}",
                color = Palette.TextStrong, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 56.dp, bottom = 4.dp).clip(Corner).clickable(enabled = e.driverUid == me) { vm.useMyCar(null) }.padding(vertical = 4.dp))
            myCarId != null -> Text("오늘 이 차로 가요", color = Palette.Navy, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 56.dp, bottom = 4.dp).clip(Corner).clickable { vm.useMyCar(myCarId) }.padding(vertical = 4.dp))
        }
        Spacer(Modifier.height(6.dp))
        // P-c: the "decide together" tools, visible instead of hidden in ⋮.
        if (!locked) Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The chat is always the first chip (QA 2026-11-04: "채팅방 들어가는 게 어딨는지 모르겠다" — it only showed on
            // shared trips). Alone, it says how a trip gets its chat room.
            if (sharedTrip) {
                LaunchedEffect(ref.groupId, chatOpen) { chatSeen = ChatSeen.get(context, ref.groupId) }
                val unread = ChatSeen.unread(chatLines, me, chatSeen)
                ToolChip(if (unread == 0) "채팅" else "채팅 · 새 글 $unread") { chatOpen = true }
            } else ToolChip("채팅") { chatHint = true }
            ToolChip(if (pickups.isEmpty()) "픽업" else "픽업 · ${pickups.size}명") { pickupOpen = true }
            ToolChip("투표") { voteOpen = true }
            ToolChip("다트 · 동네 뽑기") { dartOpen = true }
            // 팀장 2026-11-09: 일정 짜면서도 추천 — 받은 칸들을 이 날에 바로 넣는다.
            ToolChip("AI 추천") { aiOpen = true }
            // 12장: parking around the next place — also what the "주차장 찾아봐 주세요" push opens.
            if (stops.any { !it.visited && !it.deleted }) ToolChip("주차장") { parkingAround = (stops.firstOrNull { it.id == day?.parkingHintStopId && !it.visited } ?: stops.first { !it.visited && !it.deleted }).id }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dates.forEachIndexed { i, d -> DateTab(d, i + 1, selected = d == date) { vm.showDate(d) } }
        }
        HorizontalDivider(color = Palette.Line)

        if (locked) {
            // Q18: the car is moving and this phone belongs to the driver.
            Text(
                "운전 중이라 편집이 잠겼어요. 동승자 폰에서 바꿀 수 있어요.",
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextStrong,
                modifier = Modifier.fillMaxWidth().background(Palette.Sheet).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        error?.let {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("오류: $it", color = Palette.Sunday, fontSize = 15.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = vm::clearError) { Icon(Icons.Filled.Close, "닫기", tint = Palette.TextSoft) }
            }
        }

        // 경로 미리보기 (팀장 2026-11-04): the day's route on a map over the rail. On a phone the list needs the room, so the
        // map folds up as the list is pushed up and opens again when it is pulled down at the top.
        val roads by vm.roads.collectAsStateWithLifecycle()
        // 픽업 자리 = ordinary slots in the day (팀장 2026-11-09: "기존 일정들이랑 동일하게 주소가 떠 있고") — they move,
        // sort by 동선 and go to the car in that order. Only a pickup with no slot of its own (older days) keeps the line on top.
        val waitingPicks = pickups.filter { p -> p.status == com.htb.carplan.shared.model.PickupStatus.WAITING && stops.none { it.pickupUid == p.uid } }.sortedBy { it.order }
        val hasMap = waitingPicks.any { it.place.lat != 0.0 } || stops.any { !it.deleted && (it.place.lat != 0.0 || it.place.lng != 0.0) }
        val mapFull = 200.dp
        val mapFullPx = with(androidx.compose.ui.platform.LocalDensity.current) { (mapFull + 8.dp).toPx() }
        var mapFrac by rememberSaveable { androidx.compose.runtime.mutableFloatStateOf(1f) }
        val fold = remember(mapFullPx, hasMap) {
            object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                    if (!hasMap || available.y >= 0 || mapFrac <= 0f) return androidx.compose.ui.geometry.Offset.Zero
                    val before = mapFrac
                    mapFrac = (mapFrac + available.y / mapFullPx).coerceIn(0f, 1f)
                    return androidx.compose.ui.geometry.Offset(0f, (mapFrac - before) * mapFullPx)
                }
                override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                    if (!hasMap || available.y <= 0 || mapFrac >= 1f) return androidx.compose.ui.geometry.Offset.Zero
                    val before = mapFrac
                    mapFrac = (mapFrac + available.y / mapFullPx).coerceIn(0f, 1f)
                    return androidx.compose.ui.geometry.Offset(0f, (mapFrac - before) * mapFullPx)
                }
            }
        }
        if (hasMap) {
            if (mapFrac > 0.01f) Box(Modifier.fillMaxWidth().height((mapFull + 8.dp) * mapFrac).clipToBounds()) {
                PlanMap(stops, roads, Modifier.fillMaxWidth().wrapContentHeight(Alignment.Bottom, unbounded = true).padding(start = 16.dp, end = 16.dp, top = 8.dp).height(mapFull),
                    pickups = waitingPicks.map { it.name to it.place })
            }
            // Folded: one line to bring it back (pulling down at the top does the same).
            if (mapFrac < 0.15f) Text("지도 보기 ▾", fontSize = 14.sp, color = Palette.TextSoft,
                modifier = Modifier.fillMaxWidth().clickable { mapFrac = 1f }.padding(horizontal = 16.dp, vertical = 6.dp))
        }

        val listState = rememberLazyListState()
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        val reorder = rememberReorderableLazyListState(listState) { from, to ->
            vm.moveLocally(from.key as String, to.key as String)
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentFrequentTick)
        }
        // 손④: swipe the day sideways for the next / previous 일차 (same as the calendar's months).
        val dayAt = dates.indexOf(date)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).nestedScroll(fold).padding(horizontal = 16.dp)
                .sideSwipe(date, onPrev = { dates.getOrNull(dayAt - 1)?.let(vm::showDate) }, onNext = { dates.getOrNull(dayAt + 1)?.takeIf { dayAt >= 0 }?.let(vm::showDate) }),
        ) {
            item { Spacer(Modifier.height(12.dp)) }
            if (waitingPicks.isNotEmpty()) item(key = "pickups") {
                Row(Modifier.fillMaxWidth().clip(Corner).clickable { pickupOpen = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("픽업", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Palette.Background,
                        modifier = Modifier.background(Palette.TextStrong, Corner).padding(horizontal = 8.dp, vertical = 3.dp))
                    Text("  먼저 태우러 가요 · " + waitingPicks.joinToString(" → ") { "${it.name.ifBlank { "일행" }} ${it.place.name}" },
                        fontSize = 15.sp, color = Palette.TextStrong, maxLines = 2, modifier = Modifier.weight(1f))
                }
                HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(bottom = 8.dp))
            }
            // Q55: the car's 주유하기 (today only) — after its stop, or before the next place when it is "now".
            val fuel = day?.fuelNote?.takeIf { date == java.time.LocalDate.now().toString() }
            val fuelBefore = fuel?.takeIf { it.afterStopId == null || stops.none { s -> s.id == it.afterStopId } }?.let { stops.firstOrNull { !it.visited }?.id }
            itemsIndexed(stops, key = { _, s -> s.id }) { i, stop ->
                if (fuel != null && stop.id == fuelBefore) FuelRow(fuel)
                ReorderableItem(reorder, key = stop.id) { dragging ->
                    val state = when {
                        stop.visited -> NodeState.VISITED
                        stop.id == nextId -> NodeState.NEXT
                        else -> NodeState.PLANNED
                    }
                    val nextStop = stops.getOrNull(i + 1)
                    Appear(index = i) {
                        TimelineStop(
                            index = i,
                            stop = stop,
                            state = state,
                            estimate = estimates[stop.id],
                            editedBy = recentEditor(stop, me, vm::nameOf),
                            comments = comments[stop.id].orEmpty(),
                            nameOf = vm::nameOf,
                            leg = legs[stop.id],
                            railAbovePassed = i > 0 && stop.visited,
                            railBelowPassed = nextStop?.visited == true,
                            isLast = i == stops.lastIndex,
                            dragging = dragging,
                            locked = locked,
                            modifier = if (locked) Modifier else Modifier.longPressDraggableHandle(onDragStarted = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) }, onDragStopped = { vm.commitOrder() }),
                            onOpen = { if (!locked) editing = stop },
                            onRemove = { vm.remove(stop.id) },
                            onTimeTap = { if (!locked) timeFor = stop },
                            onSendComment = { vm.addComment(stop.id, it) },
                            // ③ 칸 → 채팅: "여기 어때?" 장소 카드로 일행에게 물어보기.
                            onAskChat = if (!sharedTrip || me == null) null else ({
                                scope.launch { runCatching { com.htb.carplan.shared.data.ChatRepository().sharePlace(ref.groupId, me!!, stop.place, ref.eventId, date, ask = true, refId = stop.id) } }
                                chatOpen = true
                            }),
                            record = recordByStop[stop.id],
                            onNearby = if (stop.place.lat != 0.0 || stop.place.lng != 0.0) ({ nearbyFor = stop }) else null,
                            onAddPhoto = { r -> photoFor = r.id; pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            onRemovePhoto = { r, url -> vm.removePhoto(r.id, url) },
                            onNote = { r -> noteFor = r },
                            onParking = { r -> parkingFor = r },
                        )
                    }
                }
                unplannedAfter(i).forEach { u ->
                    UnplannedRow(
                        record = u, comments = comments[u.id].orEmpty(), nameOf = vm::nameOf, locked = locked,
                        onAddPhoto = { photoFor = u.id; pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onRemovePhoto = { url -> vm.removePhoto(u.id, url) },
                        onNote = { noteFor = u }, onPlace = { placeFor = u }, onParking = { parkingFor = u },
                        onSendComment = { vm.addComment(u.id, it) },
                    )
                }
                if (fuel != null && fuel.afterStopId == stop.id) FuelRow(fuel)
            }
            if (stops.isEmpty()) items(unplanned, key = { "u-" + it.id }) { u ->
                UnplannedRow(
                    record = u, comments = comments[u.id].orEmpty(), nameOf = vm::nameOf, locked = locked,
                    onAddPhoto = { photoFor = u.id; pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onRemovePhoto = { url -> vm.removePhoto(u.id, url) },
                    onNote = { noteFor = u }, onPlace = { placeFor = u }, onParking = { parkingFor = u },
                    onSendComment = { vm.addComment(u.id, it) },
                )
            }
            if (!locked) item(key = "add") {
                Column {
                    // One [장소 추가] only — the bottom button (팀장 2026-11-04: the rail's own "+ 장소 추가" doubled it).
                    if (stops.isEmpty()) Text("아래 [장소 추가]로 첫 장소를 넣어요", fontSize = 16.sp, color = Palette.TextSoft, modifier = Modifier.padding(start = RAIL_W, top = 8.dp, bottom = 4.dp))
                    TextButton(onClick = { manualRecord = true }, modifier = Modifier.padding(start = RAIL_W)) { Text("기록 칸 직접 추가 (차 없이 세운 곳)", color = Palette.TextSoft, fontSize = 15.sp) }
                }
            }
            if (removed.isNotEmpty()) {
                item { Text("뺀 칸", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)) }
                items(removed, key = { "removed-" + it.id }) { stop ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${vm.whoDid(stop.updatedBy)} ${com.htb.carplan.shared.text.Josa.eul(stop.place.name)} 뺐어요",
                            fontSize = 16.sp, color = Palette.TextSoft, textDecoration = TextDecoration.LineThrough, modifier = Modifier.weight(1f),
                        )
                        if (!locked) TextButton(onClick = { vm.restore(stop.id) }) { Text("되살리기", color = Palette.Navy, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
                    }
                }
            }
            // 하루 전체 이동 (팀장 2026-11-10 "하루 일정 볼 때 가장 아래에 전체 이동거리"): 칸 사이 구간을 모두 더한다.
            val dayLegs = stops.dropLast(1).mapNotNull { legs[it.id] }
            if (stops.size >= 2 && dayLegs.isNotEmpty()) item(key = "day-total") {
                Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                    HorizontalDivider(color = Palette.Line)
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Bottom) {
                        Text("하루 전체 이동", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextStrong, modifier = Modifier.weight(1f))
                        val rough = dayLegs.size < stops.size - 1 || dayLegs.any { !it.exact }
                        Text((if (rough) "약 " else "") + "${km(dayLegs.sumOf { it.meters })} · ${minutes(dayLegs.sumOf { it.seconds })}",
                            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextStrong)
                    }
                    Text("${stops.size}곳 · 구간 ${stops.size - 1}개" + if (dayLegs.size < stops.size - 1) " (계산 중인 구간 빼고)" else "",
                        fontSize = 14.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 2.dp))
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }

        HorizontalDivider(color = Palette.Line)
        if (!locked) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Hidden below 3 places: nothing to sort (QA 2026-11-04).
            if (stops.count { !it.deleted && it.place.hasCoords() } >= 3) OutlinedButton(
                onClick = vm::sortShortest,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = Corner,
                border = BorderStroke(1.dp, Palette.Line),
            ) { Text(if (vm.kakaoAvailable) "동선 최소로 정렬" else "동선 정렬 (직선)", color = Palette.Navy, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) }
            Button(
                onClick = { adding = true },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = Corner,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy),
            ) { Text("장소 추가", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        }
    }

    // 장소 추가 = pick and it is in (QA 2026-11-04: 3 taps a place and the window closed each time). Time and memo are set
    // on the stop afterwards (tap the time / the card).
    if (adding) {
        var addedKeys by remember { mutableStateOf(setOf<String>()) }
        PlaceSearchSheet(vm = vm, added = addedKeys,
            onPick = { p -> val k = p.kakaoId ?: p.name; if (k !in addedKeys) { vm.addStop(p, null, ""); addedKeys = addedKeys + k } },
            onDismiss = { adding = false })
    }
    editing?.let { stop ->
        StopEditSheet(vm, existing = stop, onSave = { p, t, m -> vm.updateStop(stop.id, p, t, m); editing = null }, onDismiss = { editing = null },
            onVote = { p -> voteSeed = p; editing = null; voteOpen = true })
    }
    if (pickupOpen) PickupSheet(vm, onDismiss = { pickupOpen = false })
    if (inviteOpen) InviteSheet(vm, onDismiss = { inviteOpen = false })
    if (voteOpen) VoteSheet(ref.groupId, ref.eventId, date, nameOf = { vm.nameOf(it) }, onPickPlace = { cb -> placeQuery = ""; placePicker = cb }, onDismiss = { voteOpen = false; voteSeed = null; voteSeeds = emptyList() }, seed = voteSeed, seeds = voteSeeds)
    if (aiOpen) me?.let { u ->
        // 일정이 이미 어느 동네면(첫 칸 주소) 그 동네 채우기부터, 아니면 새 동네 추천 (팀장 2026-11-09).
        val live = stops.filterNot { it.deleted }
        com.htb.carplan.phone.record.NextTripSheet(
            u,
            fillRegion = live.firstNotNullOfOrNull { s -> s.place.address.takeIf { it.isNotBlank() }?.let { com.htb.carplan.shared.calendar.TasteBuild.region(it) } },
            existingNames = live.map { it.place.name }.toSet(),
            onAddOne = { p -> vm.addStop(p, null, com.htb.carplan.shared.calendar.StopMemo.PICKED) },
            onAdd = { t -> t.stops.forEach { p -> vm.addStop(p, null, com.htb.carplan.shared.calendar.StopMemo.AI_PICKED) }; aiOpen = false },
            onDismiss = { aiOpen = false },
        )
    }
    if (dartOpen) DartSheet(ref.groupId, ref.eventId, date, nameOf = { vm.nameOf(it) }, onDismiss = { dartOpen = false })
    if (chatOpen) com.htb.carplan.phone.ui.BackScreen(onBack = { chatOpen = false; ChatSeen.mark(context, ref.groupId) }) { closeChat -> ChatScreen(
        groupId = ref.groupId, eventId = ref.eventId, date = date, title = event?.title?.ifBlank { null } ?: "여행", me = me, nameOf = vm::nameOf,
        actions = ChatActions(
            addStop = if (locked) null else ({ p, memo -> vm.addStop(p, null, memo) }),
            inDay = { p -> stops.any { !it.deleted && ((p.kakaoId != null && it.place.kakaoId == p.kakaoId) ||
                (it.place.name == p.name && kotlin.math.abs(it.place.lat - p.lat) < 0.0005 && kotlin.math.abs(it.place.lng - p.lng) < 0.0005)) } },
            pickPlace = { q, cb -> placeQuery = q; placePicker = cb },
            openVote = { seeds -> voteSeeds = seeds; voteOpen = true },
            openDart = { dartOpen = true },
            openPickup = { pickupOpen = true },
            showDay = { d -> chatOpen = false; ChatSeen.mark(context, ref.groupId); if (d != null && d in dates) vm.showDate(d) },
            exportDay = { d -> chatOpen = false; ChatSeen.mark(context, ref.groupId); if (d != null && d in dates) vm.showDate(d); exportMenu = true },
        ),
        onDismiss = closeChat,
    ) }
    placePicker?.let { cb -> PlaceSearchSheet(vm = vm, initialQuery = placeQuery, onPick = { cb(it); placePicker = null; placeQuery = "" }, onDismiss = { placePicker = null; placeQuery = "" }) }
    noteFor?.let { r ->
        NoteDialog(initial = r.note, onSave = { vm.setNote(r.id, it); noteFor = null }, onDismiss = { noteFor = null })
    }
    parkingFor?.let { r -> ParkingDialog(r, locked, onSave = { vm.setParkingNote(r.id, it); parkingFor = null }, onDismiss = { parkingFor = null }) }
    if (manualRecord) {
        NoteDialog(title = "기록 칸 직접 추가", hint = "어디였어요? (장소 이름)", initial = "", onSave = { vm.addManualRecord(it); manualRecord = false }, onDismiss = { manualRecord = false })
    }
    placeFor?.let { r ->
        // Q36: wrong place name → pick a nearby candidate (Kakao key) or type one.
        PlaceSearchSheet(vm = vm, initialQuery = r.place.name, onPick = { vm.setRecordPlace(r.id, it); placeFor = null }, onDismiss = { placeFor = null })
    }
    timeFor?.let { stop ->
        TimeDialog(
            initial = stop.time ?: estimates[stop.id],
            onPick = { vm.setTime(stop.id, it); timeFor = null },
            onClear = { vm.setTime(stop.id, null); timeFor = null },
            onDismiss = { timeFor = null },
        )
    }
}

/** Items slide up one after another the first time the list shows (phone only; the car has no such motion). */
@Composable
private fun Appear(index: Int, content: @Composable () -> Unit) {
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = shown,
        enter = fadeIn(tween(220, delayMillis = minOf(index, 8) * 45)) + slideInVertically(tween(260, delayMillis = minOf(index, 8) * 45)) { it / 6 },
    ) { content() }
}

/**
 * One stop on the rail: node + rail segments on the left; box, comment bubbles, composer and the
 * drive to the next stop on the right. The rail above/below is solid when that leg is already driven,
 * dotted otherwise; the fill slides down when a leg becomes driven.
 */
@Composable
private fun TimelineStop(
    index: Int,
    stop: Stop,
    state: NodeState,
    estimate: String?,
    editedBy: String?,
    comments: List<Comment>,
    nameOf: (String?) -> String,
    leg: LegInfo?,
    railAbovePassed: Boolean,
    railBelowPassed: Boolean,
    isLast: Boolean,
    dragging: Boolean,
    locked: Boolean,
    modifier: Modifier,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onTimeTap: () -> Unit,
    onSendComment: (String) -> Unit,
    record: Record? = null,
    onAddPhoto: (Record) -> Unit = {},
    onRemovePhoto: (Record, String) -> Unit = { _, _ -> },
    onNote: (Record) -> Unit = {},
    onParking: (Record) -> Unit = {},
    onAskChat: (() -> Unit)? = null,
    /** 칸 근처 맛집·명소 (팀장 2026-11-04). */
    onNearby: (() -> Unit)? = null,
) {
    val belowFill by animateFloatAsState(if (railBelowPassed) 1f else 0f, tween(450), label = "railFill")
    // Collapsed: the box, photos, the latest comment and one ghost bubble. Open: every comment, the composer, photo/note buttons.
    var open by rememberSaveable(stop.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Rail(
            modifier = modifier.width(RAIL_W).fillMaxHeight(),
            state = state,
            abovePassed = railAbovePassed,
            belowFill = belowFill,
            drawAbove = index > 0,
        )
        Column(Modifier.weight(1f).padding(bottom = if (isLast) 4.dp else 0.dp)) {
            // Long-press the card itself to drag it (팀장 2026-11-04: only the small rail dot used to work).
            StopBox(stop, state, estimate, editedBy, dragging, locked, { open = !open }, onRemove, onTimeTap, record, handle = if (locked) Modifier else modifier)
            record?.let { r -> RecordStrip(r, locked, editable = open, onAddPhoto = { onAddPhoto(r) }, onRemovePhoto = { onRemovePhoto(r, it) }, onNote = { onNote(r) }, onParking = { onParking(r) }) }
            val shownComments = if (open) comments else comments.takeLast(1)
            shownComments.forEach { c -> Bubble(c, nameOf) }
            if (!open) {
                val hidden = comments.size - shownComments.size
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!locked) Text("코멘트 남기기", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 8.dp, start = 10.dp).speechBubble(ghost = true).clickable { open = true }.padding(horizontal = 14.dp, vertical = 8.dp))
                    if (hidden > 0) TextButton(onClick = { open = true }, modifier = Modifier.padding(top = 8.dp)) { Text("코멘트 ${hidden}개 더", fontSize = 15.sp, color = Palette.TextSoft) }
                    // 디자인 평가 2026-11-04: one quiet action per stop when folded; 채팅에 묻기 is in the opened stop.
                    // 근처 맛집·명소: what is around this place, right under it (팀장 2026-11-04).
                    if (onNearby != null) TextButton(onClick = onNearby, modifier = Modifier.padding(top = 8.dp)) { Text("근처 맛집·명소", fontSize = 15.sp, color = Palette.Navy, fontWeight = FontWeight.SemiBold) }
                    if (locked && record != null) Spacer(Modifier.height(8.dp))
                }
            } else {
                if (!locked) CommentComposer(onSend = onSendComment, startOpen = true)
                Row {
                    if (!locked) TextButton(onClick = onOpen) { Text("장소·시간·메모 고치기", fontSize = 15.sp, color = Palette.Navy, fontWeight = FontWeight.SemiBold) }
                    if (!locked && onAskChat != null) TextButton(onClick = onAskChat) { Text("채팅에 물어보기", fontSize = 15.sp, color = Palette.Navy, fontWeight = FontWeight.SemiBold) }
                    TextButton(onClick = { open = false }) { Text("접기", fontSize = 15.sp, color = Palette.TextSoft) }
                }
            }
            if (!isLast) LegLabel(leg, driving = state == NodeState.NEXT && false)
        }
    }
}

/** The node and the rail segments above and below it (shared drawing in ui/Rail.kt). */
@Composable
private fun Rail(modifier: Modifier, state: NodeState, abovePassed: Boolean, belowFill: Float, drawAbove: Boolean) {
    Box(
        modifier.railLine(nodeCenter = NODE / 2 + 2.dp, drawAbove = drawAbove, abovePassed = abovePassed, belowFill = belowFill),
        contentAlignment = Alignment.TopCenter,
    ) { RailNode(state, NODE, Modifier.padding(top = 2.dp)) }
}

@Composable
private fun StopBox(
    stop: Stop, state: NodeState, estimate: String?, editedBy: String?, dragging: Boolean, locked: Boolean,
    onOpen: () -> Unit, onRemove: () -> Unit, onTimeTap: () -> Unit, record: Record? = null, handle: Modifier = Modifier,
) {
    val next = state == NodeState.NEXT
    val visited = state == NodeState.VISITED
    val arrived = record?.arrivedAt?.let { hhmm(it) }
    Row(
        Modifier
            .then(handle)
            .fillMaxWidth()
            .shadow(if (dragging) 8.dp else 0.dp, Corner)
            .clip(Corner)
            .background(Palette.Background)
            .border(if (next) 2.dp else 1.dp, if (next) Palette.Teal else Palette.SheetEdge, Corner)
            .clickable(onClick = onOpen)
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Time: typed = black, estimated = grey "예상", none = "시간". Tap to set in place.
        Column(Modifier.width(68.dp).clickable(onClick = onTimeTap)) {
            when {
                // 13장: the car was here — real arrival on top, the plan small underneath.
                arrived != null -> { Text(arrived, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong); stop.time?.let { Text("예정 $it", fontSize = 13.sp, color = Palette.TextSoft) } }
                stop.time != null -> Text(stop.time!!, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (visited) Palette.TextSoft else Palette.TextStrong)
                estimate != null -> { Text("예상", fontSize = 13.sp, color = Palette.TextSoft); Text(estimate, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextSoft) }
                else -> Text("시간", fontSize = 16.sp, color = Palette.SheetEdge)
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stop.place.name, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (visited) Palette.TextSoft else Palette.TextStrong, modifier = Modifier.weight(1f, fill = false))
                if (next) Text("  다음", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Palette.Teal)
                // The car passed over it on purpose (건너뛰기): done for the route, but not "다녀옴".
                if (stop.skipped) Text("  건너뜀", fontSize = 15.sp, color = Palette.TextSoft)
            }
            val sub = listOfNotNull(
                stop.place.address.takeIf { it.isNotBlank() },
                if (!stop.place.hasCoords()) "좌표 없음" else null,
                editedBy,
            ).joinToString("  ·  ")
            if (sub.isNotEmpty()) Text(sub, fontSize = 15.sp, color = Palette.TextSoft)
            if (stop.memo.isNotBlank()) {
                val (tag, rest) = com.htb.carplan.shared.calendar.StopMemo.split(stop.memo)
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    tag?.let { Text(it, fontSize = 14.sp, color = Palette.TextSoft, modifier = Modifier.border(1.dp, Palette.Line, Corner).padding(horizontal = 6.dp, vertical = 1.dp)) }
                    if (rest.isNotBlank()) Text(rest, fontSize = 16.sp, color = Palette.ChipText, modifier = Modifier.padding(start = if (tag != null) 6.dp else 0.dp))
                }
            }
        }
        if (!locked) IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "빼기", tint = Palette.TextSoft) } else Spacer(Modifier.width(12.dp))
    }
}

/** Q55 on the rail: where the car plans to fill up (the car decides and routes; the phone only shows it). */
@Composable
private fun FuelRow(f: com.htb.carplan.shared.model.FuelNote) {
    val what = if (f.ev) "충전하기" else "주유하기"
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(RAIL_W).fillMaxHeight().railLine(NODE / 2 + 2.dp, drawAbove = true, abovePassed = false, belowFill = 0f), contentAlignment = Alignment.TopCenter) {
            Text("연료", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.TextSoft, modifier = Modifier.padding(top = 6.dp).background(Palette.Background).border(1.dp, Palette.Line, Corner).padding(horizontal = 4.dp, vertical = 1.dp))
        }
        Column(Modifier.weight(1f).padding(top = 4.dp, bottom = 12.dp)) {
            Text(
                listOfNotNull(what, f.stationName, f.priceWon?.let { "%,d원".format(it) }).joinToString("  ·  "),
                fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong,
            )
            Text(
                listOfNotNull(
                    f.percentThen?.let { "그때 약 $it%" },
                    when { f.chosen -> "차 경로에 넣음"; f.stationName != null -> "차에서 고르면 경로에 들어가요"; else -> "여기쯤 연료가 모자라요 · 차에서 최저가를 찾아요" },
                ).joinToString("  ·  "),
                fontSize = 15.sp, color = Palette.TextSoft,
            )
        }
    }
}

@Composable
private fun ToolChip(label: String, onClick: () -> Unit) {
    Text(
        label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Navy,
        modifier = Modifier.clip(Corner).border(1.dp, Palette.Line, Corner).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** 10장 8: what the car has done with the plan — "경로 바뀜 · 다음 도착 15:40", "운전자가 이전 경로 유지", "차에 반영 대기 중". */
private fun dayLine(day: com.htb.carplan.shared.model.TripDay?): String? = when (day?.status) {
    null, DayStatus.READY -> "바로 저장"
    DayStatus.DONE -> "다 다녀옴"
    DayStatus.DRIVING -> when {
        day.holdByDriver -> "운전자가 이전 경로 유지"
        day.routeSyncedVersion >= day.version -> "차에 반영됨" + (day.etaNext?.let { " · 다음 도착 $it" } ?: "")
        else -> "차에 반영 대기 중"
    }
}

/** A member's note hanging off the rail; it slides in from the rail side when it arrives. */
@Composable
private fun Bubble(c: Comment, nameOf: (String?) -> String) {
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(visibleState = shown, enter = fadeIn(tween(200)) + slideInHorizontally(tween(240)) { -it / 4 }) {
        Column(Modifier.padding(top = 8.dp, start = 10.dp, end = 24.dp).speechBubble().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(c.text, fontSize = 17.sp, color = Palette.TextStrong)
            Text(
                nameOf(c.uid) + (c.createdAt?.toDate()?.let { " · " + android.text.format.DateFormat.format("HH:mm", it) } ?: ""),
                fontSize = 14.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** "코멘트 남기기" ghost bubble that turns into a one-line field in place. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentComposer(onSend: (String) -> Unit, startOpen: Boolean = false) {
    var open by remember { mutableStateOf(startOpen) }
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    fun send() { if (text.isNotBlank()) { onSend(text); text = ""; open = false } }
    // The field takes the cursor the moment it opens, so typing starts right away.
    LaunchedEffect(open) { if (open) focus.requestFocus() }
    if (!open) {
        Text(
            "코멘트 남기기",
            fontSize = 15.sp, color = Palette.TextSoft,
            modifier = Modifier.padding(top = 8.dp, start = 10.dp).speechBubble(ghost = true).clickable { open = true }.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    } else {
        Row(Modifier.padding(top = 8.dp, start = 10.dp, end = 24.dp).speechBubble(), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                text, { text = it }, placeholder = { Text("여기서 뭐 먹을까?", fontSize = 17.sp) }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 17.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            IconButton(onClick = ::send, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "보내기", tint = if (text.isNotBlank()) Palette.Navy else Palette.SheetEdge) }
            IconButton(onClick = { open = false; text = "" }) { Icon(Icons.Filled.Close, "닫기", tint = Palette.TextSoft) }
        }
    }
}

/** Photos + 한 줄 under a stop the car reached (14장). */
@Composable
private fun RecordStrip(r: Record, locked: Boolean, editable: Boolean = true, onAddPhoto: () -> Unit, onRemovePhoto: (String) -> Unit, onNote: () -> Unit, onParking: () -> Unit = {}) {
    val tv = LocalTripVm.current
    var viewing by remember { mutableStateOf<Int?>(null) }
    viewing?.let { com.htb.carplan.phone.ui.PhotoViewer(r.photoUrls, it) { viewing = null } }
    Column(Modifier.padding(top = 8.dp, start = 4.dp)) {
        if (r.photoUrls.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                r.photoUrls.forEachIndexed { i, url ->
                    Box {
                        AsyncImage(model = url, contentDescription = "사진 크게 보기", contentScale = ContentScale.Crop, modifier = Modifier.size(96.dp).clip(Corner).clickable { viewing = i })
                        if (!locked && editable) Text("×", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).background(Color(0x66000000)).clickable { onRemovePhoto(url) }.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (r.note.isNotBlank()) Text(r.note, fontSize = 16.sp, color = Palette.TextStrong, modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp))
            if (!locked && editable) {
                TextButton(onClick = onAddPhoto, enabled = r.photoUrls.size < 10) { Text(if (r.photoUrls.isEmpty()) "사진" else "사진 ${r.photoUrls.size}/10", fontSize = 15.sp, color = Palette.Navy) }
                TextButton(onClick = onNote) { Text(if (r.note.isBlank()) "한 줄" else "한 줄 고치기", fontSize = 15.sp, color = Palette.Navy) }
            }
            r.leftAt?.let { Text("떠남 ${hhmm(it)}", fontSize = 14.sp, color = Palette.TextSoft) }
        }
        // Q58 [또 갈래요]: each member once; places pressed here come first in recommendations.
        if (tv != null) {
            val me = tv.uid.collectAsStateWithLifecycle().value
            val mine = me != null && me in r.againBy
            val bg by animateColorAsState(if (mine) Palette.Navy else Palette.Background, tween(200), label = "again")
            Text(
                if (r.againBy.isEmpty()) "또 갈래요" else "또 갈래요 ${r.againBy.size}",
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (mine) Color.White else Palette.Navy,
                modifier = Modifier.padding(top = 4.dp).clip(Corner).background(bg).border(1.dp, if (mine) Palette.Navy else Palette.Line, Corner)
                    .clickable(enabled = !locked) { tv.toggleAgain(r) }.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        // ★3: the car knows where it parked; a phone adds the spot ("B2 C-14") so the way back is easy.
        if (r.parkedLat != null && r.parkedLng != null && r.leftAt == null) {
            Row(Modifier.clip(Corner).clickable(onClick = onParking).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("주차", fontSize = 14.sp, color = Palette.TextSoft, modifier = Modifier.border(1.dp, Palette.Line, Corner).padding(horizontal = 6.dp, vertical = 1.dp))
                Text(
                    r.parkingNote.ifBlank { if (locked) "지도에서 보기" else "자리 적기 · 지도" },
                    fontSize = 16.sp, color = if (r.parkingNote.isBlank()) Palette.Navy else Palette.TextStrong, modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/** ★3: where the car is parked — a small map and the spot written by a member ("B2 C-14"). */
@Composable
private fun ParkingDialog(r: Record, locked: Boolean, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(r.parkingNote) }
    CenterSheet(onDismiss = onDismiss) {
        Column {
            Text("주차 위치", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
            Text(r.place.name.ifBlank { "이름 없는 곳" }, fontSize = 16.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
            SimpleMap(r.parkedLat!!, r.parkedLng!!, zoom = 16, markers = listOf(MapMarker(r.parkedLat!!, r.parkedLng!!, "차", MapMarker.Kind.CAR)), height = 220.dp)
            if (!locked) TextField(
                text, { text = it }, placeholder = { Text("자리 (예: B2 C-14)", fontSize = 17.sp) }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 17.sp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) else if (r.parkingNote.isNotBlank()) Text(r.parkingNote, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(if (locked) "닫기" else "취소", color = Palette.TextSoft, fontSize = 17.sp) }
                if (!locked) Button(onClick = { onSave(text) }, shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy)) { Text("저장", fontSize = 17.sp) }
            }
        }
    }
}

/** A stop the plan did not have (휴게소…): dotted-rail row with a grey node, "계획 밖", photos and note. */
@Composable
private fun UnplannedRow(
    record: Record, comments: List<Comment>, nameOf: (String?) -> String, locked: Boolean,
    onAddPhoto: () -> Unit, onRemovePhoto: (String) -> Unit, onNote: () -> Unit, onPlace: () -> Unit, onSendComment: (String) -> Unit,
    onParking: () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(RAIL_W).fillMaxHeight().railLine(NODE / 2 + 2.dp, drawAbove = true, abovePassed = true, belowFill = 1f), contentAlignment = Alignment.TopCenter) {
            RailNode(NodeState.VISITED, NODE, Modifier.padding(top = 2.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().clip(Corner).border(1.dp, Palette.SheetEdge, Corner).clickable(enabled = !locked, onClick = onPlace).padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.width(68.dp)) {
                    Text(record.arrivedAt?.let { hhmm(it) } ?: "—", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
                    Text("계획 밖", fontSize = 13.sp, color = Palette.TextSoft)
                }
                Column(Modifier.weight(1f)) {
                    Text(record.place.name.ifBlank { "이름 없는 곳" }, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
                    Text(listOfNotNull(record.place.address.takeIf { it.isNotBlank() && it != record.place.name }, if (!locked) "눌러서 장소 바꾸기" else null).joinToString("  ·  "), fontSize = 15.sp, color = Palette.TextSoft)
                }
            }
            var open by rememberSaveable(record.id) { mutableStateOf(false) }
            RecordStrip(record, locked, editable = open, onAddPhoto = onAddPhoto, onRemovePhoto = onRemovePhoto, onNote = onNote, onParking = onParking)
            (if (open) comments else comments.takeLast(1)).forEach { c -> Bubble(c, nameOf) }
            if (!open) {
                if (!locked) Text("코멘트 남기기", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 8.dp, start = 10.dp).speechBubble(ghost = true).clickable { open = true }.padding(horizontal = 14.dp, vertical = 8.dp))
            } else {
                if (!locked) CommentComposer(onSend = onSendComment, startOpen = true)
                TextButton(onClick = { open = false }, modifier = Modifier.padding(start = 4.dp)) { Text("접기", fontSize = 15.sp, color = Palette.TextSoft) }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
internal fun NoteDialog(title: String = "한 줄", hint: String = "짧게 한 줄", initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    CenterSheet(onDismiss = onDismiss) {
        Column {
            com.htb.carplan.phone.ui.SheetTitle(title)
            TextField(text, { text = it }, placeholder = { Text(hint, fontSize = 17.sp) }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, lineHeight = 26.sp),
                shape = Corner, colors = com.htb.carplan.phone.ui.sheetFieldColors(), minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
            com.htb.carplan.phone.ui.SheetActions("저장", onAction = { onSave(text) }, onCancel = onDismiss, enabled = text.isNotBlank() || initial.isNotBlank())
        }
    }
}

private fun hhmm(t: com.google.firebase.Timestamp): String = android.text.format.DateFormat.format("HH:mm", t.toDate()).toString()

/** The drive to the next stop, written beside the dotted rail. */
@Composable
private fun LegLabel(leg: LegInfo?, driving: Boolean) {
    Text(
        when {
            leg == null -> "이동"
            leg.exact -> "이동 ${minutes(leg.seconds)} · ${km(leg.meters)}"
            else -> "이동 약 ${minutes(leg.seconds)} · ${km(leg.meters)} (직선)"
        },
        fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 10.dp, bottom = 10.dp, start = 4.dp),
    )
}


/** "○○님이 방금 수정" (Q07): someone else touched this stop in the last 5 minutes. */
private fun recentEditor(stop: Stop, me: String?, nameOf: (String?) -> String): String? {
    val by = stop.updatedBy ?: return null
    if (by == me) return null
    val at = stop.updatedAt?.toDate()?.time ?: return null
    if (System.currentTimeMillis() - at > 5 * 60_000) return null
    return "${nameOf(by)}님이 방금 수정"
}

@Composable
private fun DateTab(date: String, dayNo: Int, selected: Boolean, onClick: () -> Unit) {
    val d = LocalDate.parse(date)
    // Underline tabs like the system settings pages: text only, a 2dp bar under the selected one.
    Column(Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${dayNo}일차", fontSize = 13.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 6.dp))
        Text("${d.monthValue}/${d.dayOfMonth} ${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (selected) Palette.TextStrong else Palette.TextSoft, modifier = Modifier.padding(bottom = 8.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) Palette.TextStrong else Color.Transparent))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: String?, onPick: (String) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    val parts = initial?.split(":")?.map { it.toInt() }
    val state = rememberTimePickerState(initialHour = parts?.get(0) ?: 10, initialMinute = parts?.get(1) ?: 0, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = Corner,
        containerColor = Palette.Background,
        title = { Text("도착 시각", fontSize = 20.sp) },
        text = { TimeInput(state) },
        confirmButton = { TextButton(onClick = { onPick("%02d:%02d".format(state.hour, state.minute)) }) { Text("확정", fontSize = 17.sp, color = Palette.Navy) } },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) { Text("비우기", fontSize = 17.sp, color = Palette.TextSoft) }
                TextButton(onClick = onDismiss) { Text("취소", fontSize = 17.sp, color = Palette.TextSoft) }
            }
        },
    )
}

private fun minutes(seconds: Int) = if (seconds >= 3600) "${seconds / 3600}시간 ${seconds % 3600 / 60}분" else "${maxOf(1, seconds / 60)}분"
private fun km(meters: Int) = if (meters >= 1000) "%.1fkm".format(meters / 1000.0) else "${meters}m"

private fun tripVmFactory(ref: TripRef) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = TripDayViewModel(ref.groupId, ref.eventId, ref.date) as T
}
