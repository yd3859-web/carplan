package com.htb.carplan.phone.vote

import kotlinx.coroutines.flow.first
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.clickable
import com.htb.carplan.phone.trip.kindLine
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.drawText
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.shared.data.Regions
import com.htb.carplan.shared.model.DartScope
import kotlinx.coroutines.launch
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 6장 다트 — 전체 화면 게임 (정원 2026-10-30). No map tiles: the map *is* the 3,559 읍·면·동 centres drawn
 * as dots, so Korea appears as a dot picture that we can animate freely. Pull the dart back, let go, it arcs
 * in with a shadow, sticks with a shake, a ripple lights up the 동네 around it, and the name rises from below.
 * Everyone in the group sees the same throw land (shared dart room).
 */
@Composable
fun DartGame(
    groupId: String, eventId: String?, date: String?, nameOf: (String) -> String, onDismiss: () -> Unit,
    /** 일정 추가 창에서 열면(A안): the chosen place goes back to that window instead of becoming a stop. */
    onPick: ((com.htb.carplan.shared.model.Place) -> Unit)? = null,
) {
    val vm: VoteDartViewModel = viewModel(key = "dart-$groupId-$eventId", factory = voteVmFactory(groupId, eventId, date))
    val dart by vm.dart.collectAsStateWithLifecycle()

    // Every opening is a fresh board (정원 2026-11-02): the dart waits at the bottom for the player.
    // An older result already in the room is not shown; throws made while the game is open are (mine or a friend's).
    var oldKey by remember { mutableStateOf<String?>(null) }
    var baselineDone by remember { mutableStateOf(false) }
    var iThrew by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val first = kotlinx.coroutines.withTimeoutOrNull(2_500) {
            androidx.compose.runtime.snapshotFlow { dart }.first { it != null }
        }
        if (!iThrew) oldKey = first?.let { it.id + "#" + it.throwCount }
        baselineDone = true
    }
    var resetTick by remember { mutableStateOf(0) }
    val freshDart = dart?.takeIf { (baselineDone || iThrew) && it.id + "#" + it.throwCount != oldKey }
    fun throwAgain() { oldKey = freshDart?.let { it.id + "#" + it.throwCount } ?: oldKey; resetTick++ }
    val me by vm.uid.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var regionsReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.ensureRegions(context); regionsReady = true }
    var scope by remember { mutableStateOf(DartScope.NATIONAL) }
    var region by remember { mutableStateOf("서울특별시") }
    // Q56: 내 위치 N km — N chosen by the user (remembered), the position fetched fresh, never a silent switch to 전국.
    val prefs = remember { context.getSharedPreferences("dart", android.content.Context.MODE_PRIVATE) }
    var radiusKm by remember { mutableStateOf(prefs.getFloat("radiusKm", 100f).toDouble()) }
    var here by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var locating by remember { mutableStateOf(false) }
    var permissionTick by remember { mutableStateOf(0) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionTick++ }
    LaunchedEffect(scope, permissionTick) {
        if (scope != DartScope.NEAR || !hasLocationPermission(context)) return@LaunchedEffect
        here = here ?: lastKnownLocation(context)
        locating = true
        com.htb.carplan.phone.trip.currentLocation(context)?.let { here = it.latitude to it.longitude }
        locating = false
    }
    val noPosition = scope == DartScope.NEAR && here == null
    val pool = remember(scope, region, here, regionsReady, radiusKm) {
        Regions.pool(scope, here?.first, here?.second, if (scope == DartScope.REGION) region else null, radiusKm).ifEmpty { Regions.all }
    }
    val shown = freshDart?.takeIf { it.scope == scope && (scope != DartScope.REGION || it.region == region) }
    // Changing 전국 / 내 위치 / 지역 (or the 시·도, the km) clears the board: the stuck dart goes and the dart is back in hand.
    var scopeSeen by remember { mutableStateOf(false) }
    LaunchedEffect(scope, region, radiusKm) {
        if (!scopeSeen) { scopeSeen = true; return@LaunchedEffect }
        oldKey = dart?.let { it.id + "#" + it.throwCount } ?: oldKey
        resetTick++
    }
    var flying by remember { mutableStateOf(false) }
    // 지도 가리기 (정원 2026-11-03): throw blind, the map shows once the dart sticks.
    var hideMap by remember { mutableStateOf(false) }
    var voteSeed by remember { mutableStateOf<com.htb.carplan.shared.model.Place?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        com.htb.carplan.phone.ui.Enter(com.htb.carplan.phone.ui.Entry.SCREEN) { Box(Modifier.fillMaxSize().background(Palette.Background).systemBarsPadding()) {
            Column(Modifier.fillMaxSize()) {
                // Top bar: back · title · scope. A phone is too narrow for one line (디자인 평가 2026-11-04: the title
                // was squeezed to a letter a line and the map lost its height) → title line, then the chips scroll.
                val narrow = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600
                val canAgain = shown != null && !flying
                val chips: @Composable () -> Unit = {
                    ScopeChip("전국", scope == DartScope.NATIONAL) { scope = DartScope.NATIONAL }
                    ScopeChip("내 위치", scope == DartScope.NEAR) {
                        if (!hasLocationPermission(context)) askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        scope = DartScope.NEAR
                    }
                    ScopeChip("지역", scope == DartScope.REGION) { scope = DartScope.REGION }
                    ScopeChip("지도 가리기", hideMap) { hideMap = !hideMap }
                }
                // Its place is always kept (hidden until there is a result) so nothing jumps under a finger.
                val again: @Composable () -> Unit = {
                    OutlinedButton(
                        onClick = { if (canAgain) throwAgain() }, enabled = canAgain, shape = Corner, border = androidx.compose.foundation.BorderStroke(1.dp, Palette.TextStrong),
                        modifier = Modifier.alpha(if (canAgain) 1f else 0f),
                    ) { Text("다시 던지기", fontSize = 16.sp, color = Palette.TextStrong, fontWeight = FontWeight.Bold) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "닫기", tint = Palette.TextStrong) }
                    Column(Modifier.weight(1f)) {
                        Text("다트 · 동네 뽑기", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, maxLines = 1)
                        Text(if (narrow) "흔들리는 다트를 당겼다 놓으세요" else "다트가 좌우로 흔들려요. 당겼다 놓는 순간의 방향으로 날아가고, 세게 당길수록 멀리 가요",
                            fontSize = 15.sp, color = Palette.TextSoft, maxLines = if (narrow) 1 else 2)
                    }
                    if (narrow) again()
                    else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { chips(); again() }
                    Spacer(Modifier.padding(end = if (narrow) 4.dp else 12.dp))
                }
                if (narrow) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
                if (scope == DartScope.REGION) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Regions.sidoNames.forEach { s -> ScopeChip(s.removeSuffix("특별자치도").removeSuffix("특별자치시").removeSuffix("광역시").removeSuffix("특별시").removeSuffix("도").ifBlank { s }, region == s) { region = s } }
                }
                if (scope == DartScope.NEAR) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(10.0, 30.0, 50.0, 100.0, 200.0).forEach { km ->
                        ScopeChip("${km.toInt()}km", radiusKm == km) { radiusKm = km; prefs.edit().putFloat("radiusKm", km.toFloat()).apply() }
                    }
                    Text(
                        when { locating && here == null -> "위치 찾는 중…"; noPosition -> "위치를 못 찾았어요 · [지역]에서 시·도를 골라 주세요"; else -> "" },
                        fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(start = 8.dp),
                    )
                }
                HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(top = 6.dp))

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                    if (regionsReady) DotMapBoard(
                        pool = pool,
                        hit = shown?.let { HitSpot(it.resultLat, it.resultLng, it.id + "#" + it.throwCount) },
                        onFlying = { flying = it },
                        resetTick = resetTick,
                        areaSido = if (scope == DartScope.REGION) region else null,
                        hideMap = hideMap,
                        near = if (scope == DartScope.NEAR) here?.let { Triple(it.first, it.second, radiusKm) } else null,
                        onLanded = { lat, lng ->
                            iThrew = true
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) // the dart sticks
                            if (noPosition) vm.message.value = "위치를 몰라서 던질 수 없어요 · [지역]에서 시·도를 골라 주세요"
                            else vm.throwDartAt(context, scope, here?.first, here?.second, if (scope == DartScope.REGION) region else null, lat, lng, radiusKm)
                        },
                    )
                    // Result card rises from the bottom once the dart has stuck.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = shown != null && !flying,
                        enter = slideInVertically(tween(420)) { it } + fadeIn(tween(300)),
                        modifier = if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600)
                            Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 12.dp)
                        else Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 92.dp),
                    ) {
                        val d = shown ?: return@AnimatedVisibility
                        Column(
                            Modifier.widthIn(max = 420.dp).fillMaxWidth().background(Palette.Background, Corner).border(1.dp, Palette.SheetEdge, Corner).padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // Where that is, at a glance (디자인 평가 2026-11-04): a small street map of the 동네 on top.
                            com.htb.carplan.phone.ui.SimpleMap(
                                centerLat = d.resultLat, centerLng = d.resultLng, zoom = 14,
                                markers = listOf(com.htb.carplan.phone.ui.MapMarker(d.resultLat, d.resultLng, d.resultName.split(" ").last(), com.htb.carplan.phone.ui.MapMarker.Kind.PICKUP)),
                                height = 120.dp,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(d.resultName.split(" ").first(), fontSize = 16.sp, color = Palette.TextSoft)
                            Text(d.resultName.split(" ").drop(1).joinToString(" "), fontSize = 30.sp, fontWeight = FontWeight.Black, color = Palette.TextStrong)
                            Text("${d.throwCount}번째 다트!  ·  ${if (d.thrownBy == me) "내가 던짐" else "${nameOf(d.thrownBy)}님이 던짐"}", fontSize = 16.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 2.dp))
                            // Q57: what is there — the place goes in as the stop, not the 동네's middle.
                            var cat by remember(d.id, d.throwCount) { mutableStateOf<com.htb.carplan.shared.net.KakaoCategory?>(null) }
                            var spots by remember(d.id, d.throwCount) { mutableStateOf<List<com.htb.carplan.shared.model.Place>?>(null) }
                            val uiScope = rememberCoroutineScope()
                            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(com.htb.carplan.shared.net.KakaoCategory.FOOD, com.htb.carplan.shared.net.KakaoCategory.CAFE, com.htb.carplan.shared.net.KakaoCategory.SIGHT).forEach { k ->
                                    ScopeChip(k.label, cat == k) { cat = k; spots = null; uiScope.launch { spots = vm.dartSpots(d, k) } }
                                }
                            }
                            if (cat != null) Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                                val list = spots
                                when {
                                    list == null -> Text("찾는 중…", fontSize = 15.sp, color = Palette.TextSoft)
                                    list.isEmpty() -> Text("근처에 ${com.htb.carplan.shared.text.Josa.ga(cat!!.label)} 없어요", fontSize = 15.sp, color = Palette.TextSoft)
                                    else -> list.forEach { p ->
                                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(p.name + "  평점·메뉴 ›", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextStrong,
                                                    modifier = Modifier.clickable { com.htb.carplan.phone.trip.openPlacePage(context, p) })
                                                Text(listOfNotNull(memoBadge(vm, p), p.kindLine(), p.address.ifBlank { null }).joinToString("  ·  "), fontSize = 14.sp, color = Palette.TextSoft, maxLines = 1)
                                            }
                                            // 묶음 4: not sure → put it to a vote with the others.
                                            if (onPick != null) TextButton(onClick = { onPick(p) }) { Text("이걸로", color = Palette.Navy, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
                                            else TextButton(onClick = { voteSeed = p }) { Text("투표", color = Palette.TextSoft, fontSize = 16.sp) }
                                            if (onPick == null && eventId != null) TextButton(onClick = { vm.addPlaceAsStop(p, d) }) { Text("칸으로", color = Palette.Navy, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
                                        }
                                    }
                                }
                            }
                            // D7 기억장치: 뽑힌 동네에 대표 코스가 있으면 통째로 제안 (정원 2026-11-09).
                            val course = remember(d.id, d.throwCount) {
                                com.htb.carplan.phone.record.NextTripLoader.courses(context).firstOrNull { c -> d.resultName.split(" ").any { t -> t.startsWith(c.word) } }
                            }
                            if (course != null) Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                                androidx.compose.material3.HorizontalDivider(color = Palette.Line)
                                Text("이 동네 대표 코스", fontSize = 14.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 8.dp))
                                Text(course.places.joinToString(" → "), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextStrong)
                                if (course.note.isNotBlank()) Text(course.note, fontSize = 14.sp, color = Palette.TextSoft)
                                if (onPick == null && eventId != null) {
                                    var coursePut by remember(d.id, d.throwCount) { mutableStateOf(false) }
                                    OutlinedButton(
                                        onClick = { vm.addCourseAsStops(course, d); coursePut = true }, enabled = !coursePut,
                                        shape = Corner, border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Line),
                                        modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(44.dp),
                                    ) { Text(if (coursePut) "코스를 넣었어요" else "추천코스로 여행하기", fontSize = 15.sp, color = if (coursePut) Palette.TextSoft else Palette.Navy, fontWeight = FontWeight.SemiBold) }
                                }
                            }
                            if (onPick != null) OutlinedButton(
                                onClick = { val parts = d.resultName.split(" "); onPick(com.htb.carplan.shared.model.Place(name = parts.last(), lat = d.resultLat, lng = d.resultLng, address = d.resultName)) },
                                shape = Corner, border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Line),
                                modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(48.dp),
                            ) { Text("동네 그대로 넣기", fontSize = 16.sp, color = Palette.Navy, fontWeight = FontWeight.SemiBold) }
                            else if (eventId != null) OutlinedButton(
                                onClick = { vm.addDartAsStop(d) }, shape = Corner, border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Line),
                                modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(48.dp),
                            ) { Text("해당지역 여행하기", fontSize = 16.sp, color = Palette.Navy, fontWeight = FontWeight.SemiBold) }
                            // A안: 결과를 일행 채팅방에 올려 같이 이야기 (같이 가는 사람이 있을 때만).
                            val shared = (vm.group.collectAsStateWithLifecycle().value?.memberIds?.size ?: 0) > 1
                            if (onPick == null && shared) {
                                var posted by remember(d.id, d.throwCount) { mutableStateOf(false) }
                                OutlinedButton(
                                    onClick = { vm.shareDart(d); posted = true }, enabled = !posted, shape = Corner, border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Line),
                                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(48.dp),
                                ) { Text(if (posted) "채팅에 올렸어요" else "채팅에 올리기", fontSize = 16.sp, color = if (posted) Palette.TextSoft else Palette.Navy, fontWeight = FontWeight.SemiBold) }
                            }
                            Text("마음에 안 들면 다시 당겨서 던지세요", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    message?.let { Text(it, color = Palette.TextStrong, fontSize = 16.sp, modifier = Modifier.align(Alignment.TopCenter).background(Palette.Background.copy(alpha = 0.9f), Corner).padding(horizontal = 12.dp, vertical = 8.dp)) }
                }
            }
        } }
        voteSeed?.let { seed -> VoteSheet(groupId, eventId, date, nameOf = nameOf, onPickPlace = { }, onDismiss = { voteSeed = null }, seed = seed) }
    }
}

/** Q58 label for a recommended place ("또 갈래요 2" / "가 봄"), from the memos the VM already read. */
@Composable
private fun memoBadge(vm: VoteDartViewModel, p: com.htb.carplan.shared.model.Place): String? {
    var memos by remember { mutableStateOf<Map<String, com.htb.carplan.shared.model.PlaceMemo>>(emptyMap()) }
    LaunchedEffect(Unit) { memos = vm.memos() }
    return com.htb.carplan.shared.calendar.PlaceRank.badge(p, memos)
}

@Composable
private fun ScopeChip(label: String, selected: Boolean, onClick: () -> Unit) = FilterChip(
    selected = selected, onClick = onClick, shape = Corner, label = { Text(label, fontSize = 16.sp) },
    colors = com.htb.carplan.phone.ui.inkChipColors(),
)

private data class HitSpot(val lat: Double, val lng: Double, val key: String)

/** Simple projection with a fixed latitude correction; the camera (centre + px per degree) animates. */
private const val COS_LAT = 0.80 // cos(36.5°)

/**
 * Boundary polylines from assets ("lng,lat;lng,lat…" per line) with a bounding box per line for culling.
 * sgg_lines.txt lines start with "시도A|시도B|": the 시·도 on each side (B empty = sea/border, A = B = a line inside one 시·도).
 */
private class GeoLines(val pts: List<FloatArray>, val box: List<FloatArray>, val sideA: List<String>, val sideB: List<String>)

private object MapAssets {
    @Volatile var sido: GeoLines? = null
    @Volatile var sgg: GeoLines? = null
    fun load(context: android.content.Context) {
        if (sido == null) sido = read(context, "sido_lines.txt")
        if (sgg == null) sgg = read(context, "sgg_lines.txt")
    }
    private fun read(context: android.content.Context, name: String): GeoLines {
        val pts = ArrayList<FloatArray>(); val box = ArrayList<FloatArray>()
        val sideA = ArrayList<String>(); val sideB = ArrayList<String>()
        context.assets.open(name).bufferedReader().forEachLine { raw ->
            if (raw.isBlank()) return@forEachLine
            val bar = raw.lastIndexOf('|')
            val tags = if (bar < 0) emptyList() else raw.substring(0, bar).split('|')
            sideA += tags.getOrElse(0) { "" }; sideB += tags.getOrElse(1) { "" }
            val line = if (bar < 0) raw else raw.substring(bar + 1)
            val parts = line.split(';')
            val a = FloatArray(parts.size * 2)
            var minX = 999f; var minY = 999f; var maxX = -999f; var maxY = -999f
            parts.forEachIndexed { i, p ->
                val c = p.indexOf(','); val x = p.substring(0, c).toFloat(); val y = p.substring(c + 1).toFloat()
                a[i * 2] = x; a[i * 2 + 1] = y
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
            }
            pts += a; box += floatArrayOf(minX, minY, maxX, maxY)
        }
        return GeoLines(pts, box, sideA, sideB)
    }
}

private data class Label(val name: String, val lat: Double, val lng: Double, val sido: String = "")

/** 수원시장안구 → 장안구: on a 시·도 map the 구 name alone reads faster. */
private fun shortSgg(s: String) = Regex("^(.+시)(.+구)$").find(s)?.groupValues?.get(2) ?: s

/**
 * The board: only 시·도 outlines and their names (calm, readable). When a dart sticks, the camera dives into
 * that spot and the 시·군·구 lines, their names and the nearby 동네 names fade in — detail only where it matters.
 */
@Composable
private fun DotMapBoard(
    pool: List<Regions.Region>, hit: HitSpot?, onFlying: (Boolean) -> Unit, onLanded: (Double, Double) -> Unit, resetTick: Int = 0,
    /** 지역: that 시·도 drawn as its own map — its outline and its 시·군·구 (대구 → 동구·서구·수성구…), nothing else. */
    areaSido: String? = null,
    /** 내 위치: (lat, lng, km) — 시·군·구 lines and names around, and the km circle. */
    near: Triple<Double, Double, Double>? = null,
    /** 지도 가리기: lines and names stay hidden until the dart sticks. */
    hideMap: Boolean = false,
) {
    val context = LocalContext.current
    var assetsReady by remember { mutableStateOf(MapAssets.sgg != null) }
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { MapAssets.load(context) }; assetsReady = true }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
        val boardH = h * 0.86f
        val all = Regions.all
        val sidoLabels = remember(all.size) { all.groupBy { it.sido }.entries.sortedWith(compareBy({ cityRank(it.key) }, { -it.value.size })).map { (k, v) -> Label(shortSido(k), v.map { it.lat }.average(), v.map { it.lng }.average()) } }
        val sggLabels = remember(all.size) { all.groupBy { it.sido + "|" + it.sigungu }.map { (_, v) -> Label(shortSgg(v.first().sigungu), v.map { it.lat }.average(), v.map { it.lng }.average(), v.first().sido) } }
        // 지역·내 위치: a map of its own (시·군·구 from the start) instead of the national map zoomed in.
        val localMap = areaSido != null || near != null
        val poolSidos = remember(pool) { pool.map { shortSido(it.sido) }.toSet() }

        // Scope framing (whole pool) and detail framing (the dart's 동네).
        // 내 위치 frames the whole km circle (not just the 동네 centres inside it).
        val nearLat = near?.let { it.third / 111.0 }; val nearLng = near?.let { it.third / (111.0 * COS_LAT) }
        val south = if (near != null) near.first - nearLat!! else pool.minOf { it.lat }; val north = if (near != null) near.first + nearLat!! else pool.maxOf { it.lat }
        val west = if (near != null) near.second - nearLng!! else pool.minOf { it.lng }; val east = if (near != null) near.second + nearLng!! else pool.maxOf { it.lng }
        val spanLng = max(0.05, east - west); val spanLat = max(0.05, north - south)
        val scopeK = min(w * 0.86 / (spanLng * COS_LAT), boardH * 0.86 / spanLat).toFloat()
        val detailK = max(scopeK * 2.2f, boardH / 0.6f) // about 65 km tall around the hit
        var focus by remember { mutableStateOf<Pair<Double, Double>?>(null) }
        val camLat = remember { Animatable(((north + south) / 2).toFloat()) }
        val camLng = remember { Animatable(((east + west) / 2).toFloat()) }
        val camK = remember { Animatable(scopeK) }
        LaunchedEffect(pool, focus) {
            val f = focus
            val (tLat, tLng, tK) = if (f != null) Triple(f.first.toFloat(), f.second.toFloat(), detailK) else Triple(((north + south) / 2).toFloat(), ((east + west) / 2).toFloat(), scopeK)
            val ms = if (f != null) 1100 else 600
            launch { camLat.animateTo(tLat, tween(ms)) }
            launch { camLng.animateTo(tLng, tween(ms)) }
            launch { camK.animateTo(tK, tween(ms)) }
        }
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, tween(900)) }

        fun toScreen(lat: Double, lng: Double) = Offset(
            (w / 2 + (lng - camLng.value) * camK.value * COS_LAT).toFloat(),
            (boardH / 2 - (lat - camLat.value) * camK.value).toFloat(),
        )
        fun toGeo(p: Offset) = (camLat.value - (p.y - boardH / 2) / camK.value).toDouble() to (camLng.value + (p.x - w / 2) / (camK.value * COS_LAT)).toDouble()

        // The whole dart stands above the bottom edge (it used to hang off-screen, so the pull was invisible).
        val launchAt = Offset(w / 2, h - with(density) { 200.dp.toPx() })
        // How far the dart is drawn back: follows the finger, up to a slingshot's reach.
        val maxBack = with(density) { 60.dp.toPx() }
        var pull by remember { mutableStateOf<Offset?>(null) }
        val fly = remember { Animatable(0f) }
        val wobble = remember { Animatable(0f) }
        val impact = remember { Animatable(1f) }
        var flightFrom by remember { mutableStateOf(launchAt) }
        var flightTo by remember { mutableStateOf<Offset?>(null) }
        var stuck by remember { mutableStateOf<Pair<Double, Double>?>(null) } // where the dart is, in geo
        var flying by remember { mutableStateOf(false) }
        val cs = rememberCoroutineScope()
        // A pull too short to throw: the dart springs back to rest instead of flying (the player throws, not the app).
        val springBack = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
        val grabRadius = with(density) { 120.dp.toPx() }
        val minPull = with(density) { 40.dp.toPx() }
        // The dart sways left and right in a fan (정원 2026-11-03): you can't aim at a place, only pick the moment and the power.
        val swingMax = 50f
        var swingDeg by remember { mutableStateOf(0f) }
        LaunchedEffect(Unit) {
            val t0 = androidx.compose.runtime.withFrameNanos { it }
            while (true) androidx.compose.runtime.withFrameNanos { n ->
                if (!flying && stuck == null) swingDeg = (sin((n - t0) / 1e9 * 2 * PI / 1.8) * swingMax).toFloat()
            }
        }
        fun swingDir(): Offset { val a = Math.toRadians(-90.0 + swingDeg); return Offset(kotlin.math.cos(a).toFloat(), sin(a).toFloat()) }
        // How far the dart is drawn back: with the finger, up to a slingshot's reach, straight behind where it points.
        fun backOf(p: Offset): Offset = swingDir() * -min(p.getDistance() * 0.6f, maxBack)
        // 지도 가리기: hidden until the dart sticks, then the map fades in.
        val reveal = remember { Animatable(if (hideMap) 0f else 1f) }
        LaunchedEffect(stuck != null, hideMap) { if (stuck != null || !hideMap) reveal.animateTo(1f, tween(700)) else reveal.snapTo(0f) }

        // Out of range = a miss (정원 2026-11-04: "범위 밖으로 던져진 것도 여행지로 처리돼 — 안 넘어가게"): 내 위치 = inside
        // the km circle, 지역 = inside that 시·도, 전국 = on land. A miss is not a pick: it says so and the dart comes back.
        var missed by remember { mutableStateOf(false) }
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        fun inRange(t: Offset): Boolean {
            if (near != null) {
                val c = toScreen(near.first, near.second)
                return (t - c).getDistance() <= (near.third / 111.0 * camK.value).toFloat()
            }
            val (la, lo) = toGeo(t)
            val anyNear = Regions.nearest(la, lo, Regions.all) ?: return false
            return RouteMathDeg(la, lo, anyNear.lat, anyNear.lng) < 0.06 * 0.06 && (areaSido == null || anyNear.sido == areaSido)
        }
        // (old) snap to the edge / nearest 동네 inside — kept for replays of an old result only.
        fun keepInside(t: Offset): Offset {
            if (near != null) {
                val c = toScreen(near.first, near.second)
                val r = (near.third / 111.0 * camK.value).toFloat() * 0.96f
                val d = t - c
                return if (d.getDistance() <= r) t else c + d / d.getDistance() * r
            }
            val (la, lo) = toGeo(t)
            val anyNear = Regions.nearest(la, lo, Regions.all)
            val inside = anyNear != null && RouteMathDeg(la, lo, anyNear.lat, anyNear.lng) < 0.06 * 0.06 && (areaSido == null || anyNear.sido == areaSido)
            if (inside) return t
            val n = Regions.nearest(la, lo, pool) ?: return t
            return toScreen(n.lat, n.lng)
        }
        suspend fun throwTo(target: Offset, ms: Int, from: Offset = launchAt) {
            flying = true; onFlying(true)
            flightFrom = from; flightTo = target; stuck = null
            fly.snapTo(0f); wobble.snapTo(0f); impact.snapTo(0f)
            fly.animateTo(1f, tween(ms, easing = FastOutLinearInEasing))
            stuck = toGeo(target)
            cs.launch { impact.animateTo(1f, tween(1100, easing = LinearOutSlowInEasing)) }
            wobble.snapTo(16f)
            wobble.animateTo(0f, spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMediumLow))
            flying = false; onFlying(false)
        }

        var seenKey by remember { mutableStateOf<String?>(null) }
        // [다시 던지기]: the stuck dart comes back to the hand and the map zooms back out.
        LaunchedEffect(resetTick) {
            if (resetTick == 0) return@LaunchedEffect
            stuck = null; flightTo = null; fly.snapTo(0f); focus = null; seenKey = ""
        }
        LaunchedEffect(hit?.key) {
            val s = hit ?: return@LaunchedEffect
            if (seenKey == null) {
                // Opening the game with an old result: just show it stuck in the overview, no replay, no dive.
                seenKey = s.key; stuck = s.lat to s.lng; fly.snapTo(1f); return@LaunchedEffect
            }
            seenKey = s.key
            val mine = stuck?.let { (la, lo) -> sqrt((la - s.lat) * (la - s.lat) + (lo - s.lng) * (lo - s.lng)) < spanLat * 0.25 } == true
            if (!(mine && !flying)) throwTo(toScreen(s.lat, s.lng), 650)
            stuck = s.lat to s.lng
            kotlinx.coroutines.delay(250)
            focus = s.lat to s.lng // dive into the 동네
        }

        LaunchedEffect(Unit) { if (hit == null) seenKey = "" }
        val detail = ((camK.value / detailK - 0.45f) / 0.45f).coerceIn(0f, 1f) // 0 = overview, 1 = dived in
        val ink = Palette.TextStrong
        Canvas(Modifier.fillMaxSize()) {
            val viewW0 = camLng.value - (w / 2) / (camK.value * COS_LAT); val viewW1 = camLng.value + (w / 2) / (camK.value * COS_LAT)
            val viewS0 = camLat.value - (boardH / 2) / camK.value; val viewS1 = camLat.value + (boardH / 2) / camK.value
            val mapA = appear.value * reveal.value
            fun drawLines(g: GeoLines?, color: Color, width: Float, keep: (Int) -> Boolean = { true }) {
                g ?: return
                val path = Path()
                g.pts.forEachIndexed { i, a ->
                    if (!keep(i)) return@forEachIndexed
                    val b = g.box[i]
                    if (b[2] < viewW0 || b[0] > viewW1 || b[3] < viewS0 || b[1] > viewS1) return@forEachIndexed
                    var k = 0
                    while (k < a.size) {
                        val p = toScreen(a[k + 1].toDouble(), a[k].toDouble())
                        if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                        k += 2
                    }
                }
                drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
            val sgg = MapAssets.sgg
            if (assetsReady && sgg != null && areaSido != null) {
                // 지역 map: only this 시·도 — thin 시·군·구 lines inside, a bold outline around.
                drawLines(sgg, ink.copy(alpha = 0.45f * mapA), 1.2.dp.toPx()) { i -> sgg.sideA[i] == areaSido && sgg.sideB[i] == areaSido }
                drawLines(sgg, ink.copy(alpha = 0.85f * mapA), 2.dp.toPx()) { i -> (sgg.sideA[i] == areaSido || sgg.sideB[i] == areaSido) && sgg.sideA[i] != sgg.sideB[i] }
            } else if (assetsReady && sgg != null && near != null) {
                // 내 위치 map: 시·군·구 lines all around, 시·도 borders and the coast bolder.
                drawLines(sgg, ink.copy(alpha = 0.4f * mapA), 1.2.dp.toPx()) { i -> sgg.sideA[i] == sgg.sideB[i] }
                drawLines(sgg, ink.copy(alpha = 0.8f * mapA), 1.8.dp.toPx()) { i -> sgg.sideA[i] != sgg.sideB[i] }
            } else if (assetsReady) {
                // Overview: coarse 시·도 outline. Dived in: the finer 시·군·구 lines (coast included) take over.
                if (detail > 0f) drawLines(MapAssets.sgg, ink.copy(alpha = 0.55f * detail * mapA), 1.2.dp.toPx())
                if (detail < 1f) drawLines(MapAssets.sido, ink.copy(alpha = 0.75f * (1f - detail) * mapA), 1.6.dp.toPx())
            }
            // names (a name that would overlap one already drawn is skipped, so a dense 시·도 stays readable)
            val placed = ArrayList<androidx.compose.ui.geometry.Rect>()
            fun fits(at: Offset, sz: androidx.compose.ui.unit.IntSize): Boolean {
                val r = androidx.compose.ui.geometry.Rect(at, androidx.compose.ui.geometry.Size(sz.width.toFloat(), sz.height.toFloat())).inflate(2.dp.toPx())
                if (placed.any { it.overlaps(r) }) return false
                placed += r; return true
            }
            // 내 위치: the km circle and where the car is.
            if (near != null) {
                val c = toScreen(near.first, near.second)
                val r = (near.third / 111.0 * camK.value).toFloat()
                drawCircle(ink.copy(alpha = 0.5f * mapA), r, c, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f))))
                drawCircle(ink.copy(alpha = mapA), 6.dp.toPx(), c)
                val m = measurer.measure("여기", androidx.compose.ui.text.TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ink.copy(alpha = mapA)))
                fits(c - Offset(8.dp.toPx(), 8.dp.toPx()), androidx.compose.ui.unit.IntSize(m.size.width + 20.dp.toPx().toInt(), m.size.height))
                drawText(m, topLeft = c + Offset(10.dp.toPx(), -m.size.height / 2f))
            }
            val big = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, color = Palette.TextSoft)
            if (!localMap && detail < 1f) sidoLabels.forEach { l ->
                val p = toScreen(l.lat, l.lng)
                if (p.x < 0 || p.x > w || p.y < 0 || p.y > boardH) return@forEach
                val on = l.name in poolSidos
                val st = big.copy(color = (if (on) ink else Color(0xFFB9BEC4)).copy(alpha = (1f - detail) * mapA), fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                val m = measurer.measure(l.name, st)
                // On a phone the 시·도 names crowd (서울·인천·경기): the big cities go first and always show; a 도 that
                // would sit on one steps below/above/aside, and is left out only if there is no room at all.
                val h = m.size.height.toFloat(); val wd = m.size.width.toFloat()
                val centre = p - Offset(wd / 2f, h / 2f)
                listOf(Offset.Zero, Offset(0f, h * 1.15f), Offset(0f, -h * 1.15f), Offset(wd * 0.75f, 0f), Offset(-wd * 0.75f, 0f))
                    .map { centre + it }.firstOrNull { fits(it, m.size) }?.let { drawText(m, topLeft = it) }
            }
            // The dart's 동네 and its neighbours go first so they always win a crowded spot.
            val st = stuck
            if (detail > 0f && st != null) (if (localMap) pool else all).asSequence()
                    .map { it to RouteMathDeg(it.lat, it.lng, st.first, st.second) }
                    .sortedBy { it.second }.take(14).forEach { (rg, _) ->
                        val p = toScreen(rg.lat, rg.lng)
                        if (p.x < 0 || p.x > w || p.y < 0 || p.y > boardH) return@forEach
                        val isHit = hit != null && kotlin.math.abs(rg.lat - hit.lat) < 1e-6 && kotlin.math.abs(rg.lng - hit.lng) < 1e-6
                        drawCircle((if (isHit) Palette.Teal else Color(0xFF8A9096)).copy(alpha = detail), (if (isHit) 6 else 3).dp.toPx(), p)
                        val m = measurer.measure(rg.dong ?: rg.sigungu, androidx.compose.ui.text.TextStyle(fontSize = if (isHit) 20.sp else 13.sp, fontWeight = if (isHit) FontWeight.Black else FontWeight.Normal, color = (if (isHit) ink else Palette.TextSoft).copy(alpha = detail)))
                        if (fits(p + Offset(8.dp.toPx(), -m.size.height / 2f), m.size)) drawText(m, topLeft = p + Offset(8.dp.toPx(), -m.size.height / 2f))
                    }
            val sggAlpha = if (localMap) mapA else detail
            if (sggAlpha > 0f) sggLabels.forEach { l ->
                if (areaSido != null && l.sido != areaSido) return@forEach
                // 내 위치: names outside the km circle stay faint (the dart can't land there).
                val inside = near == null || RouteMathDeg(l.lat, l.lng, near.first, near.second) <= (near.third / 111.0).let { it * it }
                val p = toScreen(l.lat, l.lng)
                if (p.x < 0 || p.x > w || p.y < 0 || p.y > boardH) return@forEach
                val m = measurer.measure(l.name, androidx.compose.ui.text.TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = (if (localMap) ink else Palette.ChipText).copy(alpha = sggAlpha * (if (inside) 1f else 0.35f))))
                val at = p - Offset(m.size.width / 2f, m.size.height / 2f)
                if (fits(at, m.size)) drawText(m, topLeft = at)
            }
            // impact ripple
            val sp = stuck?.let { toScreen(it.first, it.second) }
            if (sp != null && impact.value < 1f) {
                // The hit, bigger (디자인 평가 2026-11-04): three rings one after another, a burst of short sparks,
                // and a dark flash at the point for the first instant.
                val t = impact.value
                listOf(0f, 0.12f, 0.24f).forEachIndexed { i, delay ->
                    val k = ((t - delay) / (1f - delay)).coerceIn(0f, 1f)
                    if (k > 0f && k < 1f) drawCircle((if (missed) Palette.TextSoft else Palette.Teal).copy(alpha = (1f - k) * (0.85f - i * 0.2f)), k * (200 - i * 40).dp.toPx(), sp, style = Stroke((4 - i).dp.toPx()))
                }
                val burst = (t / 0.45f).coerceIn(0f, 1f)
                if (burst < 1f && !missed) for (a in 0 until 10) {
                    val ang = Math.toRadians(a * 36.0 + 18.0)
                    val dir = Offset(kotlin.math.cos(ang).toFloat(), kotlin.math.sin(ang).toFloat())
                    val r0 = (10 + 40 * burst).dp.toPx(); val r1 = (22 + 70 * burst).dp.toPx()
                    drawLine(ink.copy(alpha = 1f - burst), sp + dir * r0, sp + dir * r1, 3.dp.toPx(), StrokeCap.Round)
                }
                if (t < 0.18f) drawCircle(ink.copy(alpha = 0.35f * (1f - t / 0.18f)), 26.dp.toPx(), sp)
            }
            if (missed && stuck != null) {
                val m = measurer.measure("범위 밖이에요 · 다시 던져요", androidx.compose.ui.text.TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Sunday))
                val at = Offset(w / 2 - m.size.width / 2f, boardH * 0.12f)
                drawRect(Color.White.copy(alpha = 0.9f), at - Offset(12.dp.toPx(), 8.dp.toPx()), androidx.compose.ui.geometry.Size(m.size.width + 24.dp.toPx(), m.size.height + 16.dp.toPx()))
                drawText(m, topLeft = at)
            }
            if (reveal.value < 1f) {
                val m = measurer.measure("지도를 가렸어요 · 꽂히면 보여요", androidx.compose.ui.text.TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = ink.copy(alpha = 0.45f * (1f - reveal.value))))
                drawText(m, topLeft = Offset(w / 2 - m.size.width / 2f, boardH * 0.38f))
            }
            drawLine(Palette.Line, Offset(0f, boardH), Offset(w, boardH), 1.dp.toPx())
        }

        // gesture
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures(
                    // Only a drag that starts on the dart pulls it; a swipe elsewhere does nothing.
                    onDragStart = { at -> if (!flying && (at - launchAt).getDistance() < grabRadius) { pull = Offset.Zero; focus = null } },
                    onDrag = { c, d -> c.consume(); pull = pull?.plus(d) },
                    onDragCancel = { pull = null },
                    onDragEnd = {
                        val p = pull; pull = null
                        if (p == null || flying) return@detectDragGestures
                        if (p.getDistance() < minPull) { cs.launch { springBack.snapTo(backOf(p)); springBack.animateTo(Offset.Zero, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium)) }; return@detectDragGestures }
                        val dir = swingDir(); val back = backOf(p) // where it pointed the moment it was let go
                        cs.launch {
                            // let the camera finish zooming back out before the dart leaves the hand
                            while (camK.isRunning) kotlinx.coroutines.delay(16)
                            val reach = launchAt.y - boardH * 0.04f
                            val power = min(1f, p.getDistance() / (h * 0.40f))
                            val shake = Offset(gauss() * w * 0.008f, gauss() * boardH * 0.01f) // a little hand shake; it lands near the aim circle
                            val target = Offset(
                                (launchAt.x + dir.x * reach * power * 1.2f + shake.x).coerceIn(w * 0.02f, w * 0.98f),
                                (launchAt.y + dir.y * reach * power + shake.y).coerceIn(boardH * 0.02f, boardH * 0.98f),
                            )
                            // Released from where it was drawn back: the dart snaps forward out of the band.
                            val ok = inRange(target)
                            missed = !ok // known before it flies, so the hit itself already looks like a miss
                            throwTo(target, 560, from = launchAt + back)
                            if (ok) { val (lat, lng) = toGeo(target); onLanded(lat, lng) }
                            else {
                                // A miss: say it, then the dart comes back to the hand for another go.
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                kotlinx.coroutines.delay(1300)
                                stuck = null; flightTo = null; fly.snapTo(0f); missed = false
                            }
                        }
                    },
                )
            },
        )

        // aim guide + dart
        Canvas(Modifier.fillMaxSize()) {
            val p = pull
            // The fan the dart sways in, while it is in the hand.
            if (!flying && stuck == null) {
                val fr = 120.dp.toPx()
                drawArc(ink.copy(alpha = 0.2f), -90f - swingMax, swingMax * 2, true, launchAt - Offset(fr, fr), androidx.compose.ui.geometry.Size(fr * 2, fr * 2), style = Stroke(1.5.dp.toPx()))
            }
            if (p != null && p.getDistance() > 8f) {
                val power = min(1f, p.getDistance() / (size.height * 0.40f))
                val dir = swingDir()
                val end = launchAt + dir * (launchAt.y - boardH * 0.04f) * power
                drawLine(Palette.Teal, launchAt, end, 3.dp.toPx(), StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
                drawCircle(Palette.Teal, 14.dp.toPx(), end, style = Stroke(2.dp.toPx()))
                drawArc(Palette.Navy, -90f, 360f * power, false, launchAt - Offset(30.dp.toPx(), 30.dp.toPx()), androidx.compose.ui.geometry.Size(60.dp.toPx(), 60.dp.toPx()), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            }
            val t = fly.value; val target = flightTo
            when {
                p != null -> {
                    val power = min(1f, p.getDistance() / (size.height * 0.40f))
                    // Slingshot: two bands from either side of the launch spot to the dart's tail, tighter as it is drawn.
                    val b = backOf(p)
                    val tremble = if (power > 0.85f) Offset(sin(System.nanoTime() / 2e7).toFloat(), 0f) * 2.dp.toPx() else Offset.Zero
                    val at = launchAt + b + tremble
                    val heading = Math.toRadians(-90.0 + swingDeg).toFloat()
                    val tail = at - Offset(kotlin.math.cos(heading), sin(heading)) * 60.dp.toPx() * (1.5f + 0.2f * power) / 1.5f
                    val side = 46.dp.toPx()
                    listOf(launchAt + Offset(-side, 0f), launchAt + Offset(side, 0f)).forEach { a ->
                        drawLine(Palette.Navy.copy(alpha = 0.35f + 0.5f * power), a, tail, (3f - 1.5f * power).dp.toPx(), StrokeCap.Round)
                        drawCircle(Palette.Navy, 5.dp.toPx(), a)
                    }
                    drawDart(at, heading, 1.5f + 0.2f * power)
                }
                flying && target != null -> {
                    val ground = flightFrom + (target - flightFrom) * t
                    drawOval(Color(0x33000000), ground - Offset(10.dp.toPx(), 3.dp.toPx()), androidx.compose.ui.geometry.Size(20.dp.toPx() * (0.6f + t * 0.4f), 6.dp.toPx()))
                    val pos = ground + Offset(0f, -sin(PI * t).toFloat() * size.height * 0.22f)
                    // A short fading trail behind the dart in the air.
                    listOf(0.06f, 0.12f, 0.18f).forEachIndexed { i, back ->
                        val tt = (t - back).coerceAtLeast(0f)
                        val g = flightFrom + (target - flightFrom) * tt + Offset(0f, -sin(PI * tt).toFloat() * size.height * 0.22f)
                        drawCircle(Palette.Teal.copy(alpha = 0.28f - i * 0.08f), (5 - i).dp.toPx(), g)
                    }
                    drawDart(pos, atan2(target.y - flightFrom.y, target.x - flightFrom.x) + (1 - t) * -0.4f, 1.5f - 0.75f * t, wobble.value)
                }
                stuck != null -> {
                    val sp = toScreen(stuck!!.first, stuck!!.second)
                    drawDart(sp, atan2(sp.y - launchAt.y, sp.x - launchAt.x), 0.75f + 0.25f * detail, wobble.value)
                }
                else -> drawDart(launchAt + springBack.value, Math.toRadians(-90.0 + swingDeg).toFloat(), 1.5f)
            }
        }
    }
}

/** 특별시·광역시 first (서울 must not vanish under 경기), then the rest. */
private fun cityRank(sido: String) = listOf("서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종").indexOfFirst { sido.startsWith(it) }.let { if (it < 0) 99 else it }

private fun shortSido(s: String) = when {
    s.startsWith("전남광주") -> "전남·광주"
    else -> s.replace("특별자치도", "").replace("특별자치시", "").replace("광역시", "").replace("특별시", "")
        .let { if (it.endsWith("도") && it.length == 4) it[0].toString() + it[2] else it } // 경상북도 → 경북
}

/** Cheap planar distance in degrees (latitude-corrected) for "nearest 동네" labels. */
private fun RouteMathDeg(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
    val dx = (lo1 - lo2) * COS_LAT; val dy = la1 - la2
    return dx * dx + dy * dy
}

/** Steel tip, black barrel, blue flights — pointing along [heading] (radians, 0 = right). */
private fun DrawScope.drawDart(at: Offset, heading: Float, s: Float, wobbleDeg: Float = 0f) {
    rotate(Math.toDegrees(heading.toDouble()).toFloat() + wobbleDeg, at) {
        scale(s, s, at) {
            val u = 1.dp.toPx()
            drawLine(Color(0xFF9AA1A8), at, at + Offset(-10 * u, 0f), 2.5f * u, StrokeCap.Round)
            drawLine(Palette.Navy, at + Offset(-10 * u, 0f), at + Offset(-34 * u, 0f), 6 * u, StrokeCap.Round)
            drawLine(Palette.NavySoft, at + Offset(-34 * u, 0f), at + Offset(-48 * u, 0f), 2.5f * u, StrokeCap.Round)
            val b = at + Offset(-48 * u, 0f)
            drawPath(Path().apply { moveTo(b.x + 10 * u, b.y); lineTo(b.x - 6 * u, b.y - 9 * u); lineTo(b.x - 2 * u, b.y); lineTo(b.x - 6 * u, b.y + 9 * u); close() }, Palette.Teal)
        }
    }
}

private fun gauss(): Float {
    val u = max(1e-6, Random.nextDouble()); val v = Random.nextDouble()
    return (sqrt(-2 * ln(u)) * cos(2 * PI * v)).toFloat().coerceIn(-2.5f, 2.5f)
}
