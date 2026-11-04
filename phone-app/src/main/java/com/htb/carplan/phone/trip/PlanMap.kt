package com.htb.carplan.phone.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.MapMarker
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.phone.ui.SimpleMap
import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.model.Stop
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * 경로 미리보기 (정원 2026-11-04): the day's places numbered on the map with the road between them — just the route, no
 * moving car ("차량 움직이는 거 없애고 경로만, 확대 가능하게"). Pinch or double-tap to zoom, drag to move, [+]/[−], and
 * [전체] back to the whole day. 픽업 자리 are on the route too, first ("픽업하는 사람 위치를 경로에도").
 */
@Composable
fun PlanMap(stops: List<Stop>, roads: List<List<Pair<Double, Double>>>, modifier: Modifier, pickups: List<Pair<String, Place>> = emptyList()) {
    val pts = stops.filterNot { it.deleted }.filter { it.place.lat != 0.0 || it.place.lng != 0.0 }
    val picks = pickups.filter { it.second.lat != 0.0 || it.second.lng != 0.0 }
    if (pts.isEmpty() && picks.isEmpty()) return
    val line = remember(roads) { roads.flatten() }
    // Pickups come first on the day's way: a dotted-in straight leg from each to the next, then into the first stop.
    val pickLine = remember(picks, pts) { (picks.map { it.second.lat to it.second.lng } + listOfNotNull(pts.firstOrNull()?.let { it.place.lat to it.place.lng })).takeIf { picks.isNotEmpty() }.orEmpty() }
    val all = (pickLine + line).ifEmpty { pts.map { it.place.lat to it.place.lng } }
    // The user's own view once they zoom or move; null = fitted to the whole day.
    var view by remember { mutableStateOf<Triple<Double, Double, Int>?>(null) }
    var fitted by remember { mutableStateOf<Triple<Double, Double, Int>?>(null) }
    var pinch by remember { mutableFloatStateOf(1f) }
    val tilePx = with(LocalDensity.current) { 256.dp.toPx() }
    fun base() = view ?: fitted
    fun zoomBy(d: Int) { base()?.let { (la, lo, z) -> view = Triple(la, lo, (z + d).coerceIn(6, 18)) } }
    Box(modifier.clip(Corner)) {
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val b = base() ?: return@detectTransformGestures
                        var (la, lo, z) = b
                        pinch *= zoom
                        if (pinch > 1.6f && z < 18) { z += 1; pinch = 1f }
                        if (pinch < 0.62f && z > 6) { z -= 1; pinch = 1f }
                        val moved = shift(la, lo, z, tilePx, -pan.x, -pan.y)
                        view = Triple(moved.first, moved.second, z)
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { zoomBy(1) }) },
        ) {
            val v = view
            SimpleMap(
                centerLat = v?.first ?: all.first().first, centerLng = v?.second ?: all.first().second, zoom = v?.third ?: 13,
                markers = picks.mapIndexed { i, (name, p) -> MapMarker(p.lat, p.lng, "픽업 ${name.ifBlank { "${i + 1}" }}", MapMarker.Kind.ME) } +
                    pts.mapIndexed { i, s -> MapMarker(s.place.lat, s.place.lng, "${i + 1}", if (s.visited) MapMarker.Kind.STOP else MapMarker.Kind.PICKUP) },
                passed = pickLine,
                path = line,
                fit = if (v == null && all.size >= 2) all else emptyList(),
                onView = { la, lo, z -> if (view == null) fitted = Triple(la, lo, z) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            MapButton("+") { zoomBy(1) }
            MapButton("−") { zoomBy(-1) }
            if (view != null) MapButton("전체", wide = true) { view = null; pinch = 1f }
        }
    }
}

@Composable
private fun MapButton(label: String, wide: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.padding(bottom = 6.dp).size(width = if (wide) 48.dp else 36.dp, height = 36.dp).clip(Corner)
            .background(Palette.Background).border(1.dp, Palette.SheetEdge, Corner).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = if (wide) 13.sp else 18.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong) }
}

/** [lat],[lng] moved by [dx],[dy] screen pixels on a Web-Mercator map at [zoom]. */
private fun shift(lat: Double, lng: Double, zoom: Int, tilePx: Float, dx: Float, dy: Float): Pair<Double, Double> {
    val world = 2.0.pow(zoom) * tilePx
    val x = (lng + 180) / 360 * world + dx
    val y = (1 - ln(tan(Math.toRadians(lat)) + 1 / Math.cos(Math.toRadians(lat))) / PI) / 2 * world + dy
    return Math.toDegrees(atan(sinh(PI * (1 - 2 * y / world)))).coerceIn(-85.0, 85.0) to (x / world * 360 - 180)
}
