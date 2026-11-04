package com.htb.carplan.phone.trip

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.MapMarker
import com.htb.carplan.phone.ui.SimpleMap
import com.htb.carplan.shared.calendar.RouteMath
import com.htb.carplan.shared.model.Stop

/**
 * 경로 미리보기 (정원 2026-11-04: "일정을 등록하면서 위치 경로 미리보기, 기록처럼 움직이게"). The day's places numbered on
 * the map with the road between them, and a car that drives the whole day slowly, over and over (a few seconds a leg),
 * so the order of the day can be seen before going. Places already reached are grey.
 */
@Composable
fun PlanMap(stops: List<Stop>, roads: List<List<Pair<Double, Double>>>, modifier: Modifier) {
    val pts = stops.filterNot { it.deleted }.filter { it.place.lat != 0.0 || it.place.lng != 0.0 }
    if (pts.isEmpty()) return
    val line = remember(roads) { roads.flatten() }
    val all = line.ifEmpty { pts.map { it.place.lat to it.place.lng } }
    // One loop: 3 s a leg (at least 6 s), a short rest at the end.
    val legs = maxOf(1, pts.size - 1)
    val t = rememberInfiniteTransition(label = "planCar")
    val f by t.animateFloat(0f, 1.08f, infiniteRepeatable(tween(maxOf(6_000, legs * 3_000), easing = LinearEasing), RepeatMode.Restart), label = "planCarF")
    val (pos, heading, driven) = remember(line, f) { alongLine(line, f.coerceAtMost(1f)) }
    Box(modifier.clip(Corner)) {
        SimpleMap(
            centerLat = pts.first().place.lat, centerLng = pts.first().place.lng, zoom = 13,
            markers = pts.mapIndexed { i, s -> MapMarker(s.place.lat, s.place.lng, "${i + 1}", if (s.visited) MapMarker.Kind.STOP else MapMarker.Kind.PICKUP) } +
                listOfNotNull(pos?.takeIf { line.size >= 2 }?.let { MapMarker(it.first, it.second, "", MapMarker.Kind.CAR, heading) }),
            passed = all,
            path = driven,
            fit = if (all.size >= 2) all else emptyList(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The point [f] of the way along [line] (by distance), its heading, and the line up to there. */
private fun alongLine(line: List<Pair<Double, Double>>, f: Float): Triple<Pair<Double, Double>?, Double, List<Pair<Double, Double>>> {
    if (line.size < 2) return Triple(line.firstOrNull(), 0.0, emptyList())
    val segs = line.zipWithNext().map { (a, b) -> RouteMath.metersBetween(a.first, a.second, b.first, b.second) }
    var left = segs.sum() * f
    val out = mutableListOf(line.first())
    line.zipWithNext().forEachIndexed { i, (a, b) ->
        val heading = Math.toDegrees(kotlin.math.atan2((b.second - a.second) * kotlin.math.cos(Math.toRadians(a.first)), b.first - a.first))
        if (left <= segs[i] || i == segs.lastIndex) {
            val k = if (segs[i] > 0) (left / segs[i]).coerceIn(0.0, 1.0) else 1.0
            val p = (a.first + (b.first - a.first) * k) to (a.second + (b.second - a.second) * k)
            out += p
            return Triple(p, heading, out)
        }
        left -= segs[i]; out += b
    }
    return Triple(line.last(), 0.0, line)
}
