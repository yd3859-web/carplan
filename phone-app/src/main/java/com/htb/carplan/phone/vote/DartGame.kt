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
fun DartGame(groupId: String, eventId: String?, date: String?, nameOf: (String) -> String, onDismiss: () -> Unit) {
    val vm: VoteDartViewModel = viewModel(key = "dart-$groupId-$eventId", factory = voteVmFactory(groupId, eventId, date))
    val dart by vm.dart.collectAsStateWithLifecycle()
    val me by vm.uid.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var regionsReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.ensureRegions(context); regionsReady = true }
    var scope by remember { mutableStateOf(DartScope.NATIONAL) }
    var region by remember { mutableStateOf("서울특별시") }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val here = remember(scope) { if (scope == DartScope.NEAR) lastKnownLocation(context) else null }
    val pool = remember(scope, region, here, regionsReady) {
        Regions.pool(scope, here?.first, here?.second, if (scope == DartScope.REGION) region else null).ifEmpty { Regions.all }
    }
    val shown = dart?.takeIf { it.scope == scope && (scope != DartScope.REGION || it.region == region) }
    var flying by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Palette.Background).systemBarsPadding()) {
            Column(Modifier.fillMaxSize()) {
                // Top bar: back · title · scope
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "닫기", tint = Palette.TextStrong) }
                    Column(Modifier.weight(1f)) {
                        Text("다트 — 동네 뽑기", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
                        Text("다트를 아래로 당겼다 놓으세요. 꽂힌 곳에서 가장 가까운 동네가 뽑혀요", fontSize = 14.sp, color = Palette.TextSoft)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScopeChip("전국", scope == DartScope.NATIONAL) { scope = DartScope.NATIONAL }
                        ScopeChip("내 위치 100km", scope == DartScope.NEAR) {
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
                if (scope == DartScope.NEAR && here == null) Text("위치를 아직 몰라서 전국으로 보여 줘요", fontSize = 13.sp, color = Palette.TextSoft, modifier = Modifier.padding(horizontal = 20.dp))
                HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(top = 6.dp))

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (regionsReady) DotMapBoard(
                        pool = pool,
                        hit = shown?.let { HitSpot(it.resultLat, it.resultLng, it.id + "#" + it.throwCount) },
                        onFlying = { flying = it },
                        onLanded = { lat, lng -> vm.throwDartAt(context, scope, here?.first, here?.second, if (scope == DartScope.REGION) region else null, lat, lng) },
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
                            Text(d.resultName.split(" ").first(), fontSize = 15.sp, color = Palette.TextSoft)
                            Text(d.resultName.split(" ").drop(1).joinToString(" "), fontSize = 30.sp, fontWeight = FontWeight.Black, color = Palette.TextStrong)
                            Text("${d.throwCount}번째 다트!  ·  ${if (d.thrownBy == me) "내가 던짐" else "${nameOf(d.thrownBy)}님이 던짐"}", fontSize = 15.sp, color = Palette.Teal, modifier = Modifier.padding(top = 2.dp))
                            if (eventId != null) Button(
                                onClick = { vm.addDartAsStop(d) }, shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy),
                                modifier = Modifier.padding(top = 12.dp).fillMaxWidth().height(50.dp),
                            ) { Text("이 동네를 여행 칸으로", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                            Text("마음에 안 들면 다시 당겨서 던지세요", fontSize = 13.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    message?.let { Text(it, color = Palette.Teal, fontSize = 15.sp, modifier = Modifier.align(Alignment.TopCenter).padding(10.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ScopeChip(label: String, selected: Boolean, onClick: () -> Unit) = FilterChip(
    selected = selected, onClick = onClick, shape = Corner, label = { Text(label, fontSize = 15.sp) },
    colors = FilterChipDefaults.filterChipColors(containerColor = Palette.Background, labelColor = Palette.TextStrong, selectedContainerColor = Palette.Navy, selectedLabelColor = Color.White),
)

private data class HitSpot(val lat: Double, val lng: Double, val key: String)

/** Simple projection with a fixed latitude correction; the camera (centre + px per degree) animates. */
private const val COS_LAT = 0.80 // cos(36.5°)

@Composable
private fun DotMapBoard(pool: List<Regions.Region>, hit: HitSpot?, onFlying: (Boolean) -> Unit, onLanded: (Double, Double) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
        val boardH = h * 0.86f // the bottom strip holds the dart
        val all = Regions.all
        val inPool = remember(pool) { pool.toHashSet() }

        // Camera that frames the pool, animated when the scope changes.
        val south = pool.minOf { it.lat }; val north = pool.maxOf { it.lat }
        val west = pool.minOf { it.lng }; val east = pool.maxOf { it.lng }
        val spanLng = max(0.05, east - west); val spanLat = max(0.05, north - south)
        val targetK = min(w * 0.86 / (spanLng * COS_LAT), boardH * 0.86 / spanLat).toFloat()
        val camLat = remember { Animatable(((north + south) / 2).toFloat()) }
        val camLng = remember { Animatable(((east + west) / 2).toFloat()) }
        val camK = remember { Animatable(targetK) }
        val appear = remember { Animatable(0f) }
        LaunchedEffect(pool) {
            launch { camLat.animateTo(((north + south) / 2).toFloat(), tween(700)) }
            launch { camLng.animateTo(((east + west) / 2).toFloat(), tween(700)) }
            launch { camK.animateTo(targetK, tween(700)) }
        }
        LaunchedEffect(Unit) { appear.animateTo(1f, tween(1100, easing = LinearEasing)) }

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
        var from by remember { mutableStateOf(launchAt) }
        var to by remember { mutableStateOf<Offset?>(null) }
        var toGeoPt by remember { mutableStateOf<Pair<Double, Double>?>(null) }
        var flying by remember { mutableStateOf(false) }
        val cs = rememberCoroutineScope()

        suspend fun throwTo(target: Offset, ms: Int) {
            flying = true; onFlying(true)
            from = launchAt; to = target; toGeoPt = toGeo(target)
            fly.snapTo(0f); wobble.snapTo(0f); impact.snapTo(0f)
            fly.animateTo(1f, tween(ms, easing = FastOutLinearInEasing))
            cs.launch { impact.animateTo(1f, tween(900)) }
            wobble.snapTo(16f)
            wobble.animateTo(0f, spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMediumLow))
            flying = false; onFlying(false)
        }

        // A throw by anyone (or my own result coming back) lands on the dot map.
        LaunchedEffect(hit?.key) {
            val s = hit ?: return@LaunchedEffect
            val mine = toGeoPt?.let { (la, lo) -> sqrt((la - s.lat) * (la - s.lat) + (lo - s.lng) * (lo - s.lng)) < spanLat * 0.25 } == true
            if (mine && !flying) { toGeoPt = s.lat to s.lng; to = toScreen(s.lat, s.lng) }
            else { toGeoPt = s.lat to s.lng; throwTo(toScreen(s.lat, s.lng), 650) }
        }

        // ---- the map (dots) + effects
        Canvas(Modifier.fillMaxSize()) {
            val r = (camK.value * 0.006f).coerceIn(1.4.dp.toPx(), 4.5.dp.toPx())
            val centre = Offset(w / 2, boardH / 2)
            val maxD = sqrt(w * w + boardH * boardH) / 2
            val hitPt = toGeoPt?.let { toScreen(it.first, it.second) }
            val rippleR = impact.value * 160.dp.toPx()
            for (rg in all) {
                val p = toScreen(rg.lat, rg.lng)
                if (p.x < -10 || p.x > w + 10 || p.y < -10 || p.y > boardH + 10) continue
                // intro: dots appear from the centre outwards
                val a = ((appear.value * 1.3f - (p - centre).getDistance() / maxD)).coerceIn(0f, 1f)
                if (a <= 0f) continue
                val active = rg in inPool
                var color = if (active) Palette.TextStrong else Color(0xFFD6DADF)
                var rr = if (active) r else r * 0.8f
                if (hitPt != null && impact.value < 1f && active) {
                    val d = (p - hitPt).getDistance()
                    val band = 1f - (kotlin.math.abs(d - rippleR) / 26.dp.toPx()).coerceIn(0f, 1f)
                    if (band > 0f) { color = Palette.Teal; rr = r * (1f + band * 0.9f) }
                }
                drawCircle(color.copy(alpha = color.alpha * a), rr, p)
            }
            if (hitPt != null && impact.value < 1f) {
                drawCircle(Palette.Teal.copy(alpha = (1f - impact.value) * 0.8f), rippleR, hitPt, style = Stroke(2.5.dp.toPx()))
                drawCircle(Palette.Teal.copy(alpha = (1f - impact.value) * 0.4f), rippleR * 0.6f, hitPt, style = Stroke(1.5.dp.toPx()))
            }
            if (hitPt != null && !flying && fly.value >= 1f) drawCircle(Palette.Teal, 7.dp.toPx(), hitPt, style = Stroke(3.dp.toPx()))
            // bottom strip
            drawLine(Palette.Line, Offset(0f, boardH), Offset(w, boardH), 1.dp.toPx())
        }

        // ---- gesture
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { if (!flying) pull = Offset.Zero },
                    onDrag = { c, d -> c.consume(); pull = pull?.plus(d) },
                    onDragCancel = { pull = null },
                    onDragEnd = {
                        val p = pull; pull = null
                        if (p == null || p.getDistance() < 24f || flying) return@detectDragGestures
                        val reach = launchAt.y - boardH * 0.04f
                        val power = min(1f, p.getDistance() / (h * 0.40f))
                        val dir = Offset(-p.x, -p.y) / p.getDistance()
                        val shake = Offset(gauss() * w * 0.025f, gauss() * boardH * 0.03f)
                        val target = Offset(
                            (launchAt.x + dir.x * reach * power * 1.2f + shake.x).coerceIn(w * 0.02f, w * 0.98f),
                            (launchAt.y + dir.y * reach * power + shake.y).coerceIn(boardH * 0.02f, boardH * 0.98f),
                        )
                        cs.launch {
                            throwTo(target, 560)
                            val (lat, lng) = toGeo(target)
                            onLanded(lat, lng)
                        }
                    },
                )
            },
        )

        // ---- aim guide + dart + shadow
        Canvas(Modifier.fillMaxSize()) {
            val p = pull
            if (p != null && p.getDistance() > 8f) {
                val power = min(1f, p.getDistance() / (size.height * 0.40f))
                val dir = Offset(-p.x, -p.y) / p.getDistance()
                val end = launchAt + dir * (launchAt.y - boardH * 0.04f) * power
                drawLine(Palette.Teal, launchAt, end, 3.dp.toPx(), StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
                drawCircle(Palette.Teal, 14.dp.toPx(), end, style = Stroke(2.dp.toPx()))
                // power ring around the hand
                drawArc(Palette.Navy, -90f, 360f * power, false, launchAt - Offset(30.dp.toPx(), 30.dp.toPx()), androidx.compose.ui.geometry.Size(60.dp.toPx(), 60.dp.toPx()), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            }
            val t = fly.value; val target = to
            when {
                p != null -> drawDart(launchAt + p * 0.35f, atan2(-p.y, -p.x), 1.5f)
                target != null && (flying || t > 0f) -> {
                    val ground = from + (target - from) * t
                    if (flying) drawOval(Color(0x33000000), ground - Offset(10.dp.toPx(), 3.dp.toPx()), androidx.compose.ui.geometry.Size(20.dp.toPx() * (0.6f + t * 0.4f), 6.dp.toPx()))
                    val pos = ground + Offset(0f, -sin(PI * t).toFloat() * size.height * 0.22f)
                    drawDart(pos, atan2(target.y - from.y, target.x - from.x) + (1 - t) * -0.4f, 1.5f - 0.75f * t, wobble.value)
                }
                else -> drawDart(launchAt, (-PI / 2).toFloat(), 1.5f)
            }
        }
    }
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
