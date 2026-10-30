package com.htb.carplan.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/** A dot on the map: car (accent), pickup (ink), me (outlined). */
data class MapMarker(val lat: Double, val lng: Double, val label: String, val kind: Kind) { enum class Kind { CAR, PICKUP, ME, STOP } }

/**
 * Key-less map for 11장: OpenStreetMap tiles (3×3 around the centre) with markers placed by
 * Web-Mercator math. Good enough to watch the car approach; the Kakao Map SDK replaces it later.
 */
@Composable
fun SimpleMap(centerLat: Double, centerLng: Double, zoom: Int = 14, markers: List<MapMarker>, height: Dp = 260.dp, path: List<Pair<Double, Double>> = emptyList()) {
    val n = 2.0.pow(zoom)
    val cx = (centerLng + 180) / 360 * n
    val cy = (1 - ln(tan(Math.toRadians(centerLat)) + 1 / Math.cos(Math.toRadians(centerLat))) / PI) / 2 * n
    val tx = floor(cx).toInt(); val ty = floor(cy).toInt()
    BoxWithConstraints(Modifier.fillMaxWidth().height(height).clip(Corner).border(1.dp, Palette.Line, Corner).background(Palette.Panel)) {
        val tile = 256.dp
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }; val hPx = with(density) { maxHeight.toPx() }
        val tilePx = with(density) { tile.toPx() }
        // pixel of the centre inside the 3×3 mosaic, then shift so it sits mid-view
        val centerPx = ((cx - (tx - 1)) * tilePx) to ((cy - (ty - 1)) * tilePx)
        val ox = wPx / 2 - centerPx.first; val oy = hPx / 2 - centerPx.second
        for (dy in -1..1) for (dx in -1..1) {
            val x = tx + dx; val y = ty + dy
            if (y < 0 || y >= n.toInt()) continue
            AsyncImage(
                model = "https://tile.openstreetmap.org/$zoom/${Math.floorMod(x, n.toInt())}/$y.png",
                contentDescription = null, contentScale = ContentScale.FillBounds,
                modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset((ox + (dx + 1) * tilePx).toInt(), (oy + (dy + 1) * tilePx).toInt()) }.size(tile),
            )
        }
        // 11장 4: the road the car will drive (Kakao Mobility geometry), in the one accent colour.
        if (path.size >= 2) {
            Canvas(Modifier.matchParentSize()) {
                val p = Path()
                path.forEachIndexed { i, (lat, lng) ->
                    val mx = (lng + 180) / 360 * n
                    val my = (1 - ln(tan(Math.toRadians(lat)) + 1 / Math.cos(Math.toRadians(lat))) / PI) / 2 * n
                    val x = (ox + (mx - (tx - 1)) * tilePx).toFloat(); val y = (oy + (my - (ty - 1)) * tilePx).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                drawPath(p, Palette.Teal, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        markers.forEach { m ->
            val mx = (m.lng + 180) / 360 * n
            val my = (1 - ln(tan(Math.toRadians(m.lat)) + 1 / Math.cos(Math.toRadians(m.lat))) / PI) / 2 * n
            val px = ox + (mx - (tx - 1)) * tilePx; val py = oy + (my - (ty - 1)) * tilePx
            val size = if (m.kind == MapMarker.Kind.CAR) 18.dp else 14.dp
            val sizePx = with(density) { size.toPx() }
            Box(Modifier.offset { androidx.compose.ui.unit.IntOffset((px - sizePx / 2).toInt(), (py - sizePx / 2).toInt()) }) {
                Box(
                    Modifier.size(size).clip(CircleShape).background(
                        when (m.kind) { MapMarker.Kind.CAR -> Palette.Teal; MapMarker.Kind.ME -> Palette.Background; MapMarker.Kind.PICKUP -> Palette.TextStrong; MapMarker.Kind.STOP -> Palette.TextSoft },
                    ).border(2.dp, if (m.kind == MapMarker.Kind.ME) Palette.TextStrong else Palette.Background, CircleShape),
                )
                Text(m.label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.offset(x = size + 2.dp, y = (-2).dp).background(Color(0xCCFFFFFF)))
            }
        }
        Text("© OpenStreetMap", fontSize = 9.sp, color = Palette.TextSoft, modifier = Modifier.align(Alignment.BottomEnd).background(Color(0xAAFFFFFF)))
    }
}

/** Latitude of a tile row's top edge (for completeness; not used by markers). */
@Suppress("unused")
private fun tileLat(y: Int, zoom: Int): Double = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / 2.0.pow(zoom)))))
