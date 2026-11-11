package com.htb.carplan.phone.trip

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.MapMarker
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.phone.ui.SimpleMap
import com.htb.carplan.shared.calendar.RouteMath
import com.htb.carplan.shared.model.Place

/**
 * 픽업 · 차가 오는 중 (카카오T 택시 참고, 팀장 11/2): the whole screen is a flat map, the car glides along its road
 * between the 5-second position updates (driven part grey, the rest blue), the map keeps the car and my pickup place
 * in frame above the card, and the card says when it arrives, how far it still is, and [탔어요].
 */
@Composable
internal fun PickupLiveScreen(
    route: List<Pair<Double, Double>>, carPos: Pair<Double, Double>, pickup: Place, driver: String,
    etaSeconds: Long?, arrived: Boolean, onBoarded: () -> Unit, onDetails: () -> Unit, onBack: () -> Unit,
) {
    // Where the car is along the road, in meters; it moves there over the next update period instead of jumping.
    val total = remember(route) { pathLength(route) }
    val targetMeters = remember(route, carPos) { metersAlong(route, carPos) }
    val along = remember(route) { Animatable(targetMeters?.toFloat() ?: 0f) }
    LaunchedEffect(route, targetMeters) {
        val t = targetMeters?.toFloat() ?: return@LaunchedEffect
        if (t < along.value || t - along.value > 3_000f) along.snapTo(t) // a new road or a big jump: no slow slide
        else along.animateTo(t, tween(UPDATE_MS, easing = LinearEasing))
    }
    val split = if (route.size >= 2 && targetMeters != null) splitAt(route, along.value.toDouble()) else null
    val carAt = split?.point ?: carPos
    val left = split?.let { total - along.value } ?: RouteMath.metersBetween(carPos.first, carPos.second, pickup.lat, pickup.lng)

    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize()) {
            SimpleMap(
                carAt.first, carAt.second, zoom = 15,
                markers = listOf(
                    MapMarker(pickup.lat, pickup.lng, pickup.name, MapMarker.Kind.ME),
                    MapMarker(carAt.first, carAt.second, "", MapMarker.Kind.CAR, bearing = split?.bearing),
                ),
                path = split?.ahead ?: route, passed = split?.behind.orEmpty(),
                fit = listOf(carAt, pickup.lat to pickup.lng),
                modifier = Modifier.fillMaxSize(), bottomInset = 0.36f,
            )
            // Back, top left (white square on the map).
            Box(
                Modifier.statusBarsPadding().padding(16.dp).size(48.dp).shadow(6.dp, Corner).background(Palette.Background, Corner)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = Palette.TextStrong) }

            // The card.
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().shadow(16.dp, Corner).background(Palette.Background, Corner)
                    .navigationBarsPadding().padding(horizontal = 22.dp, vertical = 20.dp),
            ) {
                Text(
                    when {
                        arrived -> "차가 도착했어요"
                        etaSeconds != null && etaSeconds < 60 -> "곧 도착해요"
                        etaSeconds != null -> "${etaSeconds / 60}분 후 도착"
                        else -> "차가 오고 있어요"
                    },
                    fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong,
                )
                Text(
                    if (arrived) "${pickup.name}에 왔어요. 타면 눌러 주세요" else "${driver}님 차가 ${pickup.name}(으)로 오고 있어요",
                    fontSize = 16.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 4.dp),
                )
                if (!arrived && total > 0) {
                    val done = (along.value / total).toFloat().coerceIn(0f, 1f)
                    Box(Modifier.padding(top = 16.dp).fillMaxWidth().height(6.dp).background(Palette.Line, Corner)) {
                        Box(Modifier.fillMaxWidth(done).height(6.dp).background(Palette.Teal, Corner))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${km(along.value.toDouble())} 왔어요", fontSize = 15.sp, color = Palette.TextSoft)
                        Text("${km(left)} 남음", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Palette.Teal)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onDetails, shape = Corner, border = BorderStroke(1.dp, Palette.SheetEdge),
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) { Text("픽업 장소·순서", color = Palette.TextStrong, fontSize = 16.sp) }
                    Button(
                        onClick = onBoarded, shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy),
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) { Text("탔어요", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

/** The car reports every 5 s: the slide takes about that long so it never stops in between. */
private const val UPDATE_MS = 5_200

private fun km(m: Double) = if (m >= 1000) "%.1f km".format(m / 1000) else "${m.toInt()} m"

private fun d(a: Pair<Double, Double>, b: Pair<Double, Double>) = RouteMath.metersBetween(a.first, a.second, b.first, b.second)

private fun pathLength(path: List<Pair<Double, Double>>) = path.zipWithNext().sumOf { (a, b) -> d(a, b) }

/** How far along [path] the point nearest to [p] is (projected onto the closest segment), or null without a path. */
private fun metersAlong(path: List<Pair<Double, Double>>, p: Pair<Double, Double>): Double? {
    if (path.size < 2) return null
    var best = Double.MAX_VALUE; var at = 0.0; var start = 0.0
    for (i in 0 until path.size - 1) {
        val a = path[i]; val b = path[i + 1]; val len = d(a, b)
        // Flat projection is fine at this scale.
        val ax = a.second; val ay = a.first; val bx = b.second - ax; val by = b.first - ay
        val t = if (bx == 0.0 && by == 0.0) 0.0 else (((p.second - ax) * bx + (p.first - ay) * by) / (bx * bx + by * by)).coerceIn(0.0, 1.0)
        val q = (ay + by * t) to (ax + bx * t)
        val off = d(q, p)
        if (off < best) { best = off; at = start + len * t }
        start += len
    }
    return at
}

private class Split(val behind: List<Pair<Double, Double>>, val ahead: List<Pair<Double, Double>>, val point: Pair<Double, Double>, val bearing: Double?)

/** [path] cut at [meters]: the driven part, the rest, the point itself and the road's direction there. */
private fun splitAt(path: List<Pair<Double, Double>>, meters: Double): Split {
    var start = 0.0
    for (i in 0 until path.size - 1) {
        val a = path[i]; val b = path[i + 1]; val len = d(a, b)
        if (start + len >= meters || i == path.size - 2) {
            val t = if (len == 0.0) 0.0 else ((meters - start) / len).coerceIn(0.0, 1.0)
            val p = (a.first + (b.first - a.first) * t) to (a.second + (b.second - a.second) * t)
            return Split(path.subList(0, i + 1) + listOf(p), listOf(p) + path.subList(i + 1, path.size), p, bearing(a, b))
        }
        start += len
    }
    return Split(path, emptyList(), path.last(), null)
}

private fun bearing(from: Pair<Double, Double>, to: Pair<Double, Double>): Double? {
    if (from == to) return null
    val y = Math.sin(Math.toRadians(to.second - from.second)) * Math.cos(Math.toRadians(to.first))
    val x = Math.cos(Math.toRadians(from.first)) * Math.sin(Math.toRadians(to.first)) - Math.sin(Math.toRadians(from.first)) * Math.cos(Math.toRadians(to.first)) * Math.cos(Math.toRadians(to.second - from.second))
    return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
}
