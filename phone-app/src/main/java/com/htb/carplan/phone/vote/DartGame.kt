package com.htb.carplan.phone.vote

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
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
    val shown = dart?.takeIf { it.scope == scope && (scope != DartScope.REGION || it.region == region) }
    var flying by remember { mutableStateOf(false) }
    var voteSeed by remember { mutableStateOf<com.htb.carplan.shared.model.Place?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        com.htb.carplan.phone.ui.Enter(com.htb.carplan.phone.ui.Entry.SCREEN) { Box(Modifier.fillMaxSize().background(Palette.Background).systemBarsPadding()) {
            Column(Modifier.fillMaxSize()) {
                // Top bar: back · title · scope
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "닫기", tint = Palette.TextStrong) }
                    Column(Modifier.weight(1f)) {
                        Text("다트 — 동네 뽑기", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
                        Text("다트를 아래로 당겼다 놓으세요. 꽂힌 곳에서 가장 가까운 동네가 뽑혀요", fontSize = 15.sp, color = Palette.TextSoft)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScopeChip("전국", scope == DartScope.NATIONAL) { scope = DartScope.NATIONAL }
                        ScopeChip("내 위치", scope == DartScope.NEAR) {
                            if (!hasLocationPermission(context)) askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            scope = DartScope.NEAR
                        }
                        ScopeChip("지역", scope == DartScope.REGION) { scope = DartScope.REGION }
                    }
                    Spacer(Modifier.padding(end = 12.dp))
                }
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
                        onLanded = { lat, lng ->
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) // the dart sticks
                            if (noPosition) vm.message.value = "위치를 몰라서 던질 수 없어요 · [지역]에서 시·도를 골라 주세요"
                            else vm.throwDartAt(context, scope, here?.first, here?.second, if (scope == DartScope.REGION) region else null, lat, lng, radiusKm)
                        },
                    )
                    // Result card rises from the bottom once the dart has stuck.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = shown != null && !flying,
                        enter = slideInVertically(tween(420)) { it } + fadeIn(tween(300)),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 92.dp),
                    ) {
                        val d = shown ?: return@AnimatedVisibility
                        Column(
                            Modifier.widthIn(max = 420.dp).background(Palette.Background, Corner).border(1.dp, Palette.SheetEdge, Corner).padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(d.resultName.split(" ").first(), fontSize = 16.sp, color = Palette.TextSoft)
                            Text(d.resultName.split(" ").drop(1).joinToString(" "), fontSize = 30.sp, fontWeight = FontWeight.Black, color = Palette.TextStrong)
                            Text("${d.throwCount}번째 다트!  ·  ${if (d.thrownBy == me) "내가 던짐" else "${nameOf(d.thrownBy)}님이 던짐"}", fontSize = 16.sp, color = Palette.Teal, modifier = Modifier.padding(top = 2.dp))
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
                                    list.isEmpty() -> Text("근처에 ${cat!!.label}이(가) 없어요", fontSize = 15.sp, color = Palette.TextSoft)
                                    else -> list.forEach { p ->
                                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(p.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextStrong)
                                                Text(listOfNotNull(memoBadge(vm, p), p.address).joinToString("  ·  "), fontSize = 14.sp, color = Palette.TextSoft, maxLines = 1)
                                            }
                                            // 묶음 4: not sure → put it to a vote with the others.
                                            if (onPick != null) TextButton(onClick = { onPick(p) }) { Text("이걸로", color = Palette.Navy, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
                                            else TextButton(onClick = { voteSeed = p }) { Text("투표", color = Palette.TextSoft, fontSize = 16.sp) }
                                            if (onPick == null && eventId != null) TextButton(onClick = { vm.addPlaceAsStop(p, d) }) { Text("칸으로", color = Palette.Navy, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
                                        }
                                    }
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
                            ) { Text("동네 그대로 칸으로", fontSize = 16.sp, color = Palette.Navy, fontWeight = FontWeight.SemiBold) }
                            Text("마음에 안 들면 다시 당겨서 던지세요", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    message?.let { Text(it, color = Palette.Teal, fontSize = 16.sp, modifier = Modifier.align(Alignment.TopCenter).padding(10.dp)) }
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

/** Boundary polylines from assets ("lng,lat;lng,lat…" per line) with a bounding box per line for culling. */
private class GeoLines(val pts: List<FloatArray>, val box: List<FloatArray>)

private object MapAssets {
    @Volatile var sido: GeoLines? = null
    @Volatile var sgg: GeoLines? = null
    fun load(context: android.content.Context) {
        if (sido == null) sido = read(context, "sido_lines.txt")
        if (sgg == null) sgg = read(context, "sgg_lines.txt")
    }
    private fun read(context: android.content.Context, name: String): GeoLines {
        val pts = ArrayList<FloatArray>(); val box = ArrayList<FloatArray>()
        context.assets.open(name).bufferedReader().forEachLine { line ->
            if (line.isBlank()) return@forEachLine
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
        return GeoLines(pts, box)
    }
}

private data class Label(val name: String, val lat: Double, val lng: Double)

/**
 * The board: only 시·도 outlines and their names (calm, readable). When a dart sticks, the camera dives into
 * that spot and the 시·군·구 lines, their names and the nearby 동네 names fade in — detail only where it matters.
 */
@Composable
private fun DotMapBoard(pool: List<Regions.Region>, hit: HitSpot?, onFlying: (Boolean) -> Unit, onLanded: (Double, Double) -> Unit) {
    val context = LocalContext.current
    var assetsReady by remember { mutableStateOf(MapAssets.sgg != null) }
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { MapAssets.load(context) }; assetsReady = true }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
        val boardH = h * 0.86f
        val all = Regions.all
        val sidoLabels = remember(all.size) { all.groupBy { it.sido }.map { (k, v) -> Label(shortSido(k), v.map { it.lat }.average(), v.map { it.lng }.average()) } }
        val sggLabels = remember(all.size) { all.groupBy { it.sido + "|" + it.sigungu }.map { (_, v) -> Label(v.first().sigungu, v.map { it.lat }.average(), v.map { it.lng }.average()) } }
        val poolSidos = remember(pool) { pool.map { shortSido(it.sido) }.toSet() }

        // Scope framing (whole pool) and detail framing (the dart's 동네).
        val south = pool.minOf { it.lat }; val north = pool.maxOf { it.lat }
        val west = pool.minOf { it.lng }; val east = pool.maxOf { it.lng }
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

        val launchAt = Offset(w / 2, h - with(density) { 44.dp.toPx() })
        var pull by remember { mutableStateOf<Offset?>(null) }
        val fly = remember { Animatable(0f) }
        val wobble = remember { Animatable(0f) }
        val impact = remember { Animatable(1f) }
        var flightFrom by remember { mutableStateOf(launchAt) }
        var flightTo by remember { mutableStateOf<Offset?>(null) }
        var stuck by remember { mutableStateOf<Pair<Double, Double>?>(null) } // where the dart is, in geo
        var flying by remember { mutableStateOf(false) }
        val cs = rememberCoroutineScope()

        suspend fun throwTo(target: Offset, ms: Int) {
            flying = true; onFlying(true)
            flightFrom = launchAt; flightTo = target; stuck = null
            fly.snapTo(0f); wobble.snapTo(0f); impact.snapTo(0f)
            fly.animateTo(1f, tween(ms, easing = FastOutLinearInEasing))
            stuck = toGeo(target)
            cs.launch { impact.animateTo(1f, tween(800)) }
            wobble.snapTo(16f)
            wobble.animateTo(0f, spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMediumLow))
            flying = false; onFlying(false)
        }

        var seenKey by remember { mutableStateOf<String?>(null) }
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
            fun drawLines(g: GeoLines?, color: Color, width: Float) {
                g ?: return
                val path = Path()
                g.pts.forEachIndexed { i, a ->
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
            if (assetsReady) {
                // Overview: coarse 시·도 outline. Dived in: the finer 시·군·구 lines (coast included) take over.
                if (detail > 0f) drawLines(MapAssets.sgg, ink.copy(alpha = 0.55f * detail * appear.value), 1.2.dp.toPx())
                if (detail < 1f) drawLines(MapAssets.sido, ink.copy(alpha = 0.75f * (1f - detail) * appear.value), 1.6.dp.toPx())
            }
            // NEAR scope: the 100 km circle
            // names
            val big = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, color = Palette.TextSoft)
            if (detail < 1f) sidoLabels.forEach { l ->
                val p = toScreen(l.lat, l.lng)
                if (p.x < 0 || p.x > w || p.y < 0 || p.y > boardH) return@forEach
                val on = l.name in poolSidos
                val st = big.copy(color = (if (on) ink else Color(0xFFB9BEC4)).copy(alpha = (1f - detail) * appear.value), fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                val m = measurer.measure(l.name, st)
                drawText(m, topLeft = p - Offset(m.size.width / 2f, m.size.height / 2f))
            }
            if (detail > 0f) {
                sggLabels.forEach { l ->
                    val p = toScreen(l.lat, l.lng)
                    if (p.x < 0 || p.x > w || p.y < 0 || p.y > boardH) return@forEach
                    val m = measurer.measure(l.name, androidx.compose.ui.text.TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.ChipText.copy(alpha = detail)))
                    drawText(m, topLeft = p - Offset(m.size.width / 2f, m.size.height / 2f))
                }
                val st = stuck
                if (st != null) all.asSequence()
                    .map { it to RouteMathDeg(it.lat, it.lng, st.first, st.second) }
                    .sortedBy { it.second }.take(14).forEach { (rg, _) ->
                        val p = toScreen(rg.lat, rg.lng)
                        if (p.x < 0 || p.x > w || p.y < 0 || p.y > boardH) return@forEach
                        val isHit = hit != null && kotlin.math.abs(rg.lat - hit.lat) < 1e-6 && kotlin.math.abs(rg.lng - hit.lng) < 1e-6
                        drawCircle((if (isHit) Palette.Teal else Color(0xFF8A9096)).copy(alpha = detail), (if (isHit) 6 else 3).dp.toPx(), p)
                        val m = measurer.measure(rg.dong ?: rg.sigungu, androidx.compose.ui.text.TextStyle(fontSize = if (isHit) 20.sp else 13.sp, fontWeight = if (isHit) FontWeight.Black else FontWeight.Normal, color = (if (isHit) ink else Palette.TextSoft).copy(alpha = detail)))
                        drawText(m, topLeft = p + Offset(8.dp.toPx(), -m.size.height / 2f))
                    }
            }
            // impact ripple
            val sp = stuck?.let { toScreen(it.first, it.second) }
            if (sp != null && impact.value < 1f) {
                val r = impact.value * 140.dp.toPx()
                drawCircle(Palette.Teal.copy(alpha = (1f - impact.value) * 0.8f), r, sp, style = Stroke(3.dp.toPx()))
                drawCircle(Palette.Teal.copy(alpha = (1f - impact.value) * 0.35f), r * 0.55f, sp, style = Stroke(2.dp.toPx()))
            }
            drawLine(Palette.Line, Offset(0f, boardH), Offset(w, boardH), 1.dp.toPx())
        }

        // gesture
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { if (!flying) { pull = Offset.Zero; focus = null } },
                    onDrag = { c, d -> c.consume(); pull = pull?.plus(d) },
                    onDragCancel = { pull = null },
                    onDragEnd = {
                        val p = pull; pull = null
                        if (p == null || p.getDistance() < 24f || flying) return@detectDragGestures
                        cs.launch {
                            // let the camera finish zooming back out before the dart leaves the hand
                            while (camK.isRunning) kotlinx.coroutines.delay(16)
                            val reach = launchAt.y - boardH * 0.04f
                            val power = min(1f, p.getDistance() / (h * 0.40f))
                            val dir = Offset(-p.x, -p.y) / p.getDistance()
                            val shake = Offset(gauss() * w * 0.025f, gauss() * boardH * 0.03f)
                            val target = Offset(
                                (launchAt.x + dir.x * reach * power * 1.2f + shake.x).coerceIn(w * 0.02f, w * 0.98f),
                                (launchAt.y + dir.y * reach * power + shake.y).coerceIn(boardH * 0.02f, boardH * 0.98f),
                            )
                            throwTo(target, 560)
                            val (lat, lng) = toGeo(target)
                            onLanded(lat, lng)
                        }
                    },
                )
            },
        )

        // aim guide + dart
        Canvas(Modifier.fillMaxSize()) {
            val p = pull
            if (p != null && p.getDistance() > 8f) {
                val power = min(1f, p.getDistance() / (size.height * 0.40f))
                val dir = Offset(-p.x, -p.y) / p.getDistance()
                val end = launchAt + dir * (launchAt.y - boardH * 0.04f) * power
                drawLine(Palette.Teal, launchAt, end, 3.dp.toPx(), StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
                drawCircle(Palette.Teal, 14.dp.toPx(), end, style = Stroke(2.dp.toPx()))
                drawArc(Palette.Navy, -90f, 360f * power, false, launchAt - Offset(30.dp.toPx(), 30.dp.toPx()), androidx.compose.ui.geometry.Size(60.dp.toPx(), 60.dp.toPx()), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            }
            val t = fly.value; val target = flightTo
            when {
                p != null -> drawDart(launchAt + p * 0.35f, atan2(-p.y, -p.x), 1.5f)
                flying && target != null -> {
                    val ground = flightFrom + (target - flightFrom) * t
                    drawOval(Color(0x33000000), ground - Offset(10.dp.toPx(), 3.dp.toPx()), androidx.compose.ui.geometry.Size(20.dp.toPx() * (0.6f + t * 0.4f), 6.dp.toPx()))
                    val pos = ground + Offset(0f, -sin(PI * t).toFloat() * size.height * 0.22f)
                    drawDart(pos, atan2(target.y - flightFrom.y, target.x - flightFrom.x) + (1 - t) * -0.4f, 1.5f - 0.75f * t, wobble.value)
                }
                stuck != null -> {
                    val sp = toScreen(stuck!!.first, stuck!!.second)
                    drawDart(sp, atan2(sp.y - launchAt.y, sp.x - launchAt.x), 0.75f + 0.25f * detail, wobble.value)
                }
                else -> drawDart(launchAt, (-PI / 2).toFloat(), 1.5f)
            }
        }
    }
}

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
