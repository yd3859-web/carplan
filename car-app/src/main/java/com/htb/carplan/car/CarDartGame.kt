package com.htb.carplan.car

import kotlinx.coroutines.flow.first
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.FastOutLinearInEasing
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htb.carplan.shared.data.Regions
import com.htb.carplan.shared.model.DartScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import com.htb.carplan.shared.net.KakaoCategory
import com.htb.carplan.shared.model.Place
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.clickable

// Same board as the phone (vote/DartGame.kt), drawn with the car's colours. Keep the two in step.
private object Palette {
    val Background: Color @Composable get() = LocalCarColors.current.bg
    val TextStrong: Color @Composable get() = LocalCarColors.current.ink
    val TextSoft: Color @Composable get() = LocalCarColors.current.soft
    val Line: Color @Composable get() = LocalCarColors.current.line
    val SheetEdge: Color @Composable get() = LocalCarColors.current.sheetEdge
    val Navy: Color @Composable get() = LocalCarColors.current.ink
    val NavySoft: Color @Composable get() = if (LocalCarColors.current.dark) Color(0xFFD0D4D8) else Color(0xFF2B2F33)
    val Teal: Color @Composable get() = LocalCarColors.current.accent
    val ChipText: Color @Composable get() = LocalCarColors.current.soft
    val OnNavy: Color @Composable get() = LocalCarColors.current.onInk
}
private val Corner = RoundedCornerShape(4.dp)

/** 6장 차 다트 (주차 중): full screen, the same throw-and-dive game as the phone, sharing the group's dart room. */
@Composable
fun CarDartGame(
    vm: CarHomeViewModel, onDismiss: () -> Unit,
    /** 일정 추가 창에서 열면(A안): the chosen place goes back to that window instead of today's trip. */
    onPick: ((Place) -> Unit)? = null,
) {
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
    val vehicle by vm.vehicle.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var regionsReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.ensureRegions(); regionsReady = true }
    var scope by remember { mutableStateOf(DartScope.NATIONAL) }
    var region by remember { mutableStateOf("서울특별시") }
    // Q56: "내 위치 N km" — the driver picks N (정원 2026-10-31).
    var radiusKm by remember { mutableStateOf(100.0) }
    // Q56 3: the car's position comes from the navigation (asked once when the game opens), not the car document.
    val live by vm.live.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.requestLocation() }
    val here = if (scope == DartScope.NEAR) (live.lat?.let { la -> live.lng?.let { lo -> la to lo } } ?: vm.lastLocation()) else null
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

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        CarEnter(Entry.SCREEN) {
        Box(Modifier.fillMaxSize().background(Palette.Background)) {
            Column(Modifier.fillMaxSize()) {
                val compact = LocalCompact.current
                val scopes: @Composable () -> Unit = {
                    CarScopeChip("전국", scope == DartScope.NATIONAL) { scope = DartScope.NATIONAL }
                    CarScopeChip("내 위치", scope == DartScope.NEAR) { scope = DartScope.NEAR }
                    CarScopeChip("지역", scope == DartScope.REGION) { scope = DartScope.REGION }
                    CarScopeChip("지도 가리기", hideMap) { hideMap = !hideMap }
                    // After a result: back to the bottom for another go (the room keeps counting throws).
                    // Its place is always kept (hidden until there is a result) so the chips never jump under a finger.
                    val canAgain = shown != null && !flying
                    OutlinedButton(
                        onClick = { if (canAgain) throwAgain() }, enabled = canAgain, shape = Corner, border = androidx.compose.foundation.BorderStroke(1.dp, Palette.TextStrong),
                        modifier = Modifier.heightIn(min = 64.dp).alpha(if (canAgain) 1f else 0f),
                    ) { Text("다시 던지기", fontSize = 24.sp, color = Palette.TextStrong, fontWeight = FontWeight.Bold) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CarBackButton(onBack = onDismiss)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text("다트 — 동네 뽑기", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
                        Text(if (compact) "흔들리는 다트를 당겼다 놓기" else "좌우로 흔들리는 다트를 당겼다 놓으세요 · 놓는 순간 그 방향으로", fontSize = 20.sp, color = Palette.TextSoft, maxLines = 1)
                    }
                    if (!compact) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { scopes() }
                }
                // Half screen: the three choices get their own line under the title.
                if (compact) Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { scopes() }
                if (scope == DartScope.REGION) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Regions.sidoNames.forEach { s -> CarScopeChip(shortSido(s), region == s) { region = s } }
                }
                if (scope == DartScope.NEAR) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10.0, 30.0, 50.0, 100.0, 200.0).forEach { km -> CarScopeChip("${km.toInt()}km", radiusKm == km) { radiusKm = km } }
                }
                if (scope == DartScope.NEAR && here == null) Text("차 위치를 아직 몰라서 전국으로 보여 줘요", fontSize = 20.sp, color = Palette.TextSoft, modifier = Modifier.padding(horizontal = 28.dp))
                HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(top = 8.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (regionsReady) DotMapBoard(
                        pool = pool,
                        hit = shown?.let { HitSpot(it.resultLat, it.resultLng, it.id + "#" + it.throwCount) },
                        onFlying = { flying = it },
                        resetTick = resetTick,
                        areaSido = if (scope == DartScope.REGION) region else null,
                        hideMap = hideMap,
                        near = if (scope == DartScope.NEAR) here?.let { Triple(it.first, it.second, radiusKm) } else null,
                        onLanded = { lat, lng -> iThrew = true; vm.throwDartAt(scope, here?.first, here?.second, if (scope == DartScope.REGION) region else null, lat, lng, radiusKm) },
                    )
                    androidx.compose.animation.AnimatedVisibility(
                        visible = shown != null && !flying,
                        enter = slideInVertically(tween(420)) { it } + fadeIn(tween(300)),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(start = 20.dp, end = if (LocalCompact.current) 20.dp else 28.dp, bottom = 110.dp),
                    ) {
                        val d = shown ?: return@AnimatedVisibility
                        Column(
                            Modifier.widthIn(max = 520.dp).background(Palette.Background, Corner).border(1.dp, Palette.SheetEdge, Corner).padding(22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(d.resultName.split(" ").first(), fontSize = 20.sp, color = Palette.TextSoft)
                            Text(d.resultName.split(" ").drop(1).joinToString(" "), fontSize = 38.sp, fontWeight = FontWeight.Black, color = Palette.TextStrong)
                            Text("${d.throwCount}번째 다트!  ·  ${if (d.thrownBy == vm.ownerUid) "내가 던짐" else "${vm.nameOf(d.thrownBy)}님이 던짐"}", fontSize = 20.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 2.dp))
                            // Q57: what is there in that 동네 — one kind at a time (one Kakao call per press), then a stop.
                            val uiScope = rememberCoroutineScope()
                            var cat by remember(d.id, d.throwCount) { mutableStateOf<KakaoCategory?>(null) }
                            var spots by remember(d.id, d.throwCount) { mutableStateOf<List<Place>?>(null) }
                            Row(Modifier.padding(top = 14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(KakaoCategory.FOOD, KakaoCategory.CAFE, KakaoCategory.SIGHT).forEach { k ->
                                    val on = cat == k
                                    OutlinedButton(
                                        onClick = { cat = k; spots = null; uiScope.launch { spots = vm.dartSpots(d.resultLat, d.resultLng, k) } },
                                        shape = Corner, border = androidx.compose.foundation.BorderStroke(1.dp, if (on) Palette.TextStrong else Palette.SheetEdge),
                                        modifier = Modifier.weight(1f).height(76.dp),
                                    ) { Text(k.label, fontSize = 24.sp, color = Palette.TextStrong, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal) }
                                }
                            }
                            if (cat != null) Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                when {
                                    spots == null -> Text("찾는 중", fontSize = 20.sp, color = Palette.TextSoft)
                                    spots!!.isEmpty() -> Text("근처에서 못 찾았어요", fontSize = 20.sp, color = Palette.TextSoft)
                                    else -> spots!!.forEach { p ->
                                        Row(
                                            Modifier.fillMaxWidth().heightIn(min = 76.dp).clickable { if (onPick != null) onPick(p) else { vm.addPlaceAsStop(p, "다트 추천 · ${cat!!.label}"); onDismiss() } },
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(p.name, fontSize = 24.sp, color = Palette.TextStrong, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                                            Text("넣기", fontSize = 20.sp, color = Palette.TextStrong, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                            Button(
                                onClick = {
                                    if (onPick != null) { val parts = d.resultName.split(" "); onPick(Place(name = parts.last(), lat = d.resultLat, lng = d.resultLng, address = d.resultName)) }
                                    else { vm.addDartAsStop(); onDismiss() }
                                }, shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy, contentColor = Palette.OnNavy),
                                modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(76.dp),
                            ) { Text("이 동네 그대로 넣기", fontSize = 24.sp, fontWeight = FontWeight.SemiBold) }
                            // A안 채팅: 같이 가는 여행이면 결과를 일행 채팅방(폰)에 올림.
                            if (onPick == null && vm.canShareDart()) {
                                var posted by remember(d.id, d.throwCount) { mutableStateOf(false) }
                                OutlinedButton(
                                    onClick = { vm.shareDart(); posted = true }, enabled = !posted, shape = Corner,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Palette.SheetEdge),
                                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(76.dp),
                                ) { Text(if (posted) "일행 채팅에 보냈어요" else "일행 채팅에 보내기", fontSize = 24.sp, color = if (posted) Palette.TextSoft else Palette.TextStrong, fontWeight = FontWeight.SemiBold) }
                            }
                        }
                    }
                    error?.let { Text(it, color = Palette.TextStrong, fontSize = 20.sp, modifier = Modifier.align(Alignment.TopCenter).padding(10.dp)) }
                }
            }
        }
    }
    }
}

@Composable
private fun CarScopeChip(label: String, selected: Boolean, onClick: () -> Unit) = FilterChip(
    selected = selected, onClick = onClick, shape = Corner, modifier = Modifier.heightIn(min = 64.dp), label = { Text(label, fontSize = 24.sp, modifier = Modifier.padding(horizontal = 6.dp)) },
    colors = FilterChipDefaults.filterChipColors(containerColor = Palette.Background, labelColor = Palette.TextStrong, selectedContainerColor = Palette.Navy, selectedLabelColor = Palette.OnNavy),
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
    // Draw lambdas are not composable: read the theme colours here.
    val colors = LocalCarColors.current
    val cTeal = colors.accent; val cNavy = colors.ink; val cSoft = colors.soft; val cLine = colors.line
    val cOff = colors.railPassed; val cDot = colors.soft
    val dartColors = DartColors(barrel = colors.ink, shaft = if (colors.dark) Color(0xFFD0D4D8) else Color(0xFF2B2F33), flight = colors.accent)
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

        // The dart never lands outside the range (정원 2026-11-03): 내 위치 = inside the km circle,
        // 지역 = inside that 시·도, 전국 = on land. A throw that would go out lands on the edge / the nearest 동네 inside.
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
            cs.launch { impact.animateTo(1f, tween(1100, easing = androidx.compose.animation.core.LinearOutSlowInEasing)) }
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
            val big = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, color = cSoft) // 차 최소 20
            if (!localMap && detail < 1f) sidoLabels.forEach { l ->
                val p = toScreen(l.lat, l.lng)
                if (p.x < 0 || p.x > w || p.y < 0 || p.y > boardH) return@forEach
                val on = l.name in poolSidos
                val st = big.copy(color = (if (on) ink else cOff).copy(alpha = (1f - detail) * mapA), fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                val m = measurer.measure(l.name, st)
                // Big cities first and always shown; a 도 on top of one steps aside (같은 규칙: 폰 다트).
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
                        drawCircle((if (isHit) cTeal else cDot).copy(alpha = detail), (if (isHit) 6 else 3).dp.toPx(), p)
                        val m = measurer.measure(rg.dong ?: rg.sigungu, androidx.compose.ui.text.TextStyle(fontSize = if (isHit) 20.sp else 13.sp, fontWeight = if (isHit) FontWeight.Black else FontWeight.Normal, color = (if (isHit) ink else cSoft).copy(alpha = detail)))
                        if (fits(p + Offset(8.dp.toPx(), -m.size.height / 2f), m.size)) drawText(m, topLeft = p + Offset(8.dp.toPx(), -m.size.height / 2f))
                    }
            val sggAlpha = if (localMap) mapA else detail
            if (sggAlpha > 0f) sggLabels.forEach { l ->
                if (areaSido != null && l.sido != areaSido) return@forEach
                // 내 위치: names outside the km circle stay faint (the dart can't land there).
                val inside = near == null || RouteMathDeg(l.lat, l.lng, near.first, near.second) <= (near.third / 111.0).let { it * it }
                val p = toScreen(l.lat, l.lng)
                if (p.x < 0 || p.x > w || p.y < 0 || p.y > boardH) return@forEach
                val m = measurer.measure(l.name, androidx.compose.ui.text.TextStyle(fontSize = if (localMap) 20.sp else 16.sp, fontWeight = FontWeight.Bold, color = (if (localMap) ink else cSoft).copy(alpha = sggAlpha * (if (inside) 1f else 0.35f))))
                val at = p - Offset(m.size.width / 2f, m.size.height / 2f)
                if (fits(at, m.size)) drawText(m, topLeft = at)
            }
            // impact ripple
            val sp = stuck?.let { toScreen(it.first, it.second) }
            if (sp != null && impact.value < 1f) {
                // The hit, bigger (디자인 평가 2026-11-04): three rings, a burst of sparks, a flash — parked only (게임은 주차 중).
                val t = impact.value
                listOf(0f, 0.12f, 0.24f).forEachIndexed { i, delay ->
                    val k = ((t - delay) / (1f - delay)).coerceIn(0f, 1f)
                    if (k > 0f && k < 1f) drawCircle(cTeal.copy(alpha = (1f - k) * (0.85f - i * 0.2f)), k * (240 - i * 50).dp.toPx(), sp, style = Stroke((5 - i).dp.toPx()))
                }
                val burst = (t / 0.45f).coerceIn(0f, 1f)
                if (burst < 1f) for (a in 0 until 10) {
                    val ang = Math.toRadians(a * 36.0 + 18.0)
                    val dir = Offset(kotlin.math.cos(ang).toFloat(), kotlin.math.sin(ang).toFloat())
                    val r0 = (12 + 50 * burst).dp.toPx(); val r1 = (28 + 90 * burst).dp.toPx()
                    drawLine(ink.copy(alpha = 1f - burst), sp + dir * r0, sp + dir * r1, 4.dp.toPx(), StrokeCap.Round)
                }
                if (t < 0.18f) drawCircle(ink.copy(alpha = 0.35f * (1f - t / 0.18f)), 32.dp.toPx(), sp)
            }
            if (reveal.value < 1f) {
                val m = measurer.measure("지도를 가렸어요 · 꽂히면 보여요", androidx.compose.ui.text.TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ink.copy(alpha = 0.45f * (1f - reveal.value))))
                drawText(m, topLeft = Offset(w / 2 - m.size.width / 2f, boardH * 0.38f))
            }
            drawLine(cLine, Offset(0f, boardH), Offset(w, boardH), 1.dp.toPx())
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
                            throwTo(keepInside(target), 560, from = launchAt + back)
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
            // The fan the dart sways in, while it is in the hand.
            if (!flying && stuck == null) {
                val fr = 120.dp.toPx()
                drawArc(ink.copy(alpha = 0.2f), -90f - swingMax, swingMax * 2, true, launchAt - Offset(fr, fr), androidx.compose.ui.geometry.Size(fr * 2, fr * 2), style = Stroke(1.5.dp.toPx()))
            }
            if (p != null && p.getDistance() > 8f) {
                val power = min(1f, p.getDistance() / (size.height * 0.40f))
                val dir = swingDir()
                val end = launchAt + dir * (launchAt.y - boardH * 0.04f) * power
                drawLine(cTeal, launchAt, end, 3.dp.toPx(), StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
                drawCircle(cTeal, 14.dp.toPx(), end, style = Stroke(2.dp.toPx()))
                drawArc(cNavy, -90f, 360f * power, false, launchAt - Offset(30.dp.toPx(), 30.dp.toPx()), androidx.compose.ui.geometry.Size(60.dp.toPx(), 60.dp.toPx()), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
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
                        drawLine(cNavy.copy(alpha = 0.35f + 0.5f * power), a, tail, (3f - 1.5f * power).dp.toPx(), StrokeCap.Round)
                        drawCircle(cNavy, 5.dp.toPx(), a)
                    }
                    drawDart(dartColors, at, heading, 1.5f + 0.2f * power)
                }
                flying && target != null -> {
                    val ground = flightFrom + (target - flightFrom) * t
                    drawOval(Color(0x33000000), ground - Offset(10.dp.toPx(), 3.dp.toPx()), androidx.compose.ui.geometry.Size(20.dp.toPx() * (0.6f + t * 0.4f), 6.dp.toPx()))
                    val pos = ground + Offset(0f, -sin(PI * t).toFloat() * size.height * 0.22f)
                    // A short fading trail behind the dart in the air.
                    listOf(0.06f, 0.12f, 0.18f).forEachIndexed { i, back ->
                        val tt = (t - back).coerceAtLeast(0f)
                        val g = flightFrom + (target - flightFrom) * tt + Offset(0f, -sin(PI * tt).toFloat() * size.height * 0.22f)
                        drawCircle(cTeal.copy(alpha = 0.28f - i * 0.08f), (5 - i).dp.toPx(), g)
                    }
                    drawDart(dartColors, pos, atan2(target.y - flightFrom.y, target.x - flightFrom.x) + (1 - t) * -0.4f, 1.5f - 0.75f * t, wobble.value)
                }
                stuck != null -> {
                    val sp = toScreen(stuck!!.first, stuck!!.second)
                    drawDart(dartColors, sp, atan2(sp.y - launchAt.y, sp.x - launchAt.x), 0.75f + 0.25f * detail, wobble.value)
                }
                else -> drawDart(dartColors, launchAt + springBack.value, Math.toRadians(-90.0 + swingDeg).toFloat(), 1.5f)
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

private data class DartColors(val barrel: Color, val shaft: Color, val flight: Color)

/** Steel tip, ink barrel, blue flights — pointing along [heading] (radians, 0 = right). */
private fun DrawScope.drawDart(c: DartColors, at: Offset, heading: Float, s: Float, wobbleDeg: Float = 0f) {
    rotate(Math.toDegrees(heading.toDouble()).toFloat() + wobbleDeg, at) {
        scale(s, s, at) {
            val u = 1.dp.toPx()
            drawLine(Color(0xFF9AA1A8), at, at + Offset(-10 * u, 0f), 2.5f * u, StrokeCap.Round)
            drawLine(c.barrel, at + Offset(-10 * u, 0f), at + Offset(-34 * u, 0f), 6 * u, StrokeCap.Round)
            drawLine(c.shaft, at + Offset(-34 * u, 0f), at + Offset(-48 * u, 0f), 2.5f * u, StrokeCap.Round)
            val b = at + Offset(-48 * u, 0f)
            drawPath(Path().apply { moveTo(b.x + 10 * u, b.y); lineTo(b.x - 6 * u, b.y - 9 * u); lineTo(b.x - 2 * u, b.y); lineTo(b.x - 6 * u, b.y + 9 * u); close() }, c.flight)
        }
    }
}

private fun gauss(): Float {
    val u = max(1e-6, Random.nextDouble()); val v = Random.nextDouble()
    return (sqrt(-2 * ln(u)) * cos(2 * PI * v)).toFloat().coerceIn(-2.5f, 2.5f)
}
