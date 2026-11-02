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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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
import coil3.network.httpHeaders
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/** A dot on the map: car (accent, drawn as a car pointing along [bearing]), pickup (ink), me (outlined). */
data class MapMarker(val lat: Double, val lng: Double, val label: String, val kind: Kind, val bearing: Double? = null) { enum class Kind { CAR, PICKUP, ME, STOP } }

/**
 * Key-less map for 11장: OpenStreetMap tiles (3×3 around the centre) with markers placed by
 * Web-Mercator math. Good enough to watch the car approach; the Kakao Map SDK replaces it later.
 */
@Composable
fun SimpleMap(
    centerLat: Double, centerLng: Double, zoom: Int = 14, markers: List<MapMarker>, height: Dp = 260.dp,
    path: List<Pair<Double, Double>> = emptyList(),
    /** Road already driven: drawn grey under [path]. */
    passed: List<Pair<Double, Double>> = emptyList(),
    /** Points that must all be on screen: centre and zoom are chosen from them (overrides the two above). */
    fit: List<Pair<Double, Double>> = emptyList(),
) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(height).clip(Corner).border(1.dp, Palette.Line, Corner).background(Palette.Panel)) {
        val tile = 256.dp
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }; val hPx = with(density) { maxHeight.toPx() }
        val tilePx = with(density) { tile.toPx() }
        val (cLat, cLng, z) = if (fit.size >= 2) fitView(fit, wPx / tilePx, hPx / tilePx) else Triple(centerLat, centerLng, zoom)
        val n = 2.0.pow(z)
        val cx = (cLng + 180) / 360 * n
        val cy = (1 - ln(tan(Math.toRadians(cLat)) + 1 / Math.cos(Math.toRadians(cLat))) / PI) / 2 * n
        val tx = floor(cx).toInt(); val ty = floor(cy).toInt()
        val zoom = z
        // pixel of the centre inside the 3×3 mosaic, then shift so it sits mid-view
        val centerPx = ((cx - (tx - 1)) * tilePx) to ((cy - (ty - 1)) * tilePx)
        val ox = wPx / 2 - centerPx.first; val oy = hPx / 2 - centerPx.second
        val rx = kotlin.math.ceil(wPx / 2 / tilePx).toInt() + 1; val ry = kotlin.math.ceil(hPx / 2 / tilePx).toInt() + 1
        for (dy in -ry..ry) for (dx in -rx..rx) {
            val x = tx + dx; val y = ty + dy
            if (y < 0 || y >= n.toInt()) continue
            AsyncImage(
                model = tileRequest(zoom, Math.floorMod(x, n.toInt()), y),
                contentDescription = null, contentScale = ContentScale.FillBounds,
                // Unbounded: a map lower than one tile would otherwise squash every tile and leave gaps between rows.
                modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset((ox + (dx + 1) * tilePx).toInt(), (oy + (dy + 1) * tilePx).toInt()) }
                    .wrapContentSize(Alignment.TopStart, unbounded = true).size(tile),
            )
        }
        // 11장 4: the road the car will drive (Kakao Mobility geometry), in the one accent colour.
        // Driven part grey, the rest in the accent colour.
        if (path.size >= 2 || passed.size >= 2) {
            val grey = Palette.TextSoft
            Canvas(Modifier.matchParentSize()) {
                fun line(points: List<Pair<Double, Double>>): Path = Path().also { p ->
                    points.forEachIndexed { i, (lat, lng) ->
                        val mx = (lng + 180) / 360 * n
                        val my = (1 - ln(tan(Math.toRadians(lat)) + 1 / Math.cos(Math.toRadians(lat))) / PI) / 2 * n
                        val x = (ox + (mx - (tx - 1)) * tilePx).toFloat(); val y = (oy + (my - (ty - 1)) * tilePx).toFloat()
                        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                    }
                }
                if (passed.size >= 2) drawPath(line(passed), grey, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (path.size >= 2) drawPath(line(path), Palette.Teal, style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        markers.forEach { m ->
            val mx = (m.lng + 180) / 360 * n
            val my = (1 - ln(tan(Math.toRadians(m.lat)) + 1 / Math.cos(Math.toRadians(m.lat))) / PI) / 2 * n
            val px = ox + (mx - (tx - 1)) * tilePx; val py = oy + (my - (ty - 1)) * tilePx
            val size = if (m.kind == MapMarker.Kind.CAR) 30.dp else 14.dp
            val sizePx = with(density) { size.toPx() }
            Box(Modifier.offset { androidx.compose.ui.unit.IntOffset((px - sizePx / 2).toInt(), (py - sizePx / 2).toInt()) }) {
                if (m.kind == MapMarker.Kind.CAR) CarShape(size, m.bearing ?: 0.0)
                else Box(
                    Modifier.size(size).clip(CircleShape).background(
                        when (m.kind) { MapMarker.Kind.CAR -> Palette.Teal; MapMarker.Kind.ME -> Palette.Background; MapMarker.Kind.PICKUP -> Palette.TextStrong; MapMarker.Kind.STOP -> Palette.TextSoft },
                    ).border(2.dp, if (m.kind == MapMarker.Kind.ME) Palette.TextStrong else Palette.Background, CircleShape),
                )
                Text(m.label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.offset(x = size + 2.dp, y = (-2).dp).background(Color(0xCCFFFFFF)))
            }
        }
        Text(TILE_ATTRIBUTION, fontSize = 10.sp, color = Palette.TextSoft, modifier = Modifier.align(Alignment.BottomEnd).background(Color(0xAAFFFFFF)))
    }
}


/** Tile source: OpenStreetMap, identified with our app name as its usage policy asks (demo-scale use only). */
internal const val TILE_URL = "https://tile.openstreetmap.org/%d/%d/%d.png"
internal const val TILE_ATTRIBUTION = "© OpenStreetMap"
internal const val TILE_USER_AGENT = "carplan/0.1 (Pleos Connect contest demo)"
@Composable
internal fun tileRequest(zoom: Int, x: Int, y: Int): coil3.request.ImageRequest =
    coil3.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
        .data(TILE_URL.format(zoom, x, y))
        // Cache key carries a version so tiles cached before the user-agent fix (block images) are not reused.
        .memoryCacheKey(TILE_URL.format(zoom, x, y) + "#v2").diskCacheKey(TILE_URL.format(zoom, x, y) + "#v2")
        .httpHeaders(coil3.network.NetworkHeaders.Builder().set("User-Agent", TILE_USER_AGENT).build())
        .build()


/** The car seen from above, nose up, turned to [bearing] (degrees from north): body in the accent, glass white. */
@Composable
private fun CarShape(size: Dp, bearing: Double) {
    val body = Palette.Teal
    Canvas(Modifier.size(size).graphicsLayer { rotationZ = bearing.toFloat() }) {
        val w = this.size.width * 0.56f; val h = this.size.height * 0.92f
        val left = (this.size.width - w) / 2; val top = (this.size.height - h) / 2
        val r = androidx.compose.ui.geometry.CornerRadius(w * 0.32f)
        // White halo so the car reads on any tile, then the body, windscreen and rear window.
        drawRoundRect(Color.White, androidx.compose.ui.geometry.Offset(left - 2.dp.toPx(), top - 2.dp.toPx()), androidx.compose.ui.geometry.Size(w + 4.dp.toPx(), h + 4.dp.toPx()), r)
        drawRoundRect(body, androidx.compose.ui.geometry.Offset(left, top), androidx.compose.ui.geometry.Size(w, h), r)
        drawRoundRect(Color.White, androidx.compose.ui.geometry.Offset(left + w * 0.16f, top + h * 0.2f), androidx.compose.ui.geometry.Size(w * 0.68f, h * 0.18f), androidx.compose.ui.geometry.CornerRadius(w * 0.1f))
        drawRoundRect(Color.White, androidx.compose.ui.geometry.Offset(left + w * 0.18f, top + h * 0.72f), androidx.compose.ui.geometry.Size(w * 0.64f, h * 0.1f), androidx.compose.ui.geometry.CornerRadius(w * 0.08f))
    }
}

/** Centre and the largest zoom (≤ 16) at which every point fits inside [tilesW]×[tilesH] tiles with a margin. */
private fun fitView(points: List<Pair<Double, Double>>, tilesW: Float, tilesH: Float): Triple<Double, Double, Int> {
    fun mx(lng: Double) = (lng + 180) / 360
    fun my(lat: Double) = (1 - ln(tan(Math.toRadians(lat)) + 1 / Math.cos(Math.toRadians(lat))) / PI) / 2
    val xs = points.map { mx(it.second) }; val ys = points.map { my(it.first) }
    val spanX = (xs.max() - xs.min()).coerceAtLeast(1e-9); val spanY = (ys.max() - ys.min()).coerceAtLeast(1e-9)
    var z = 16
    while (z > 3 && (spanX * 2.0.pow(z) > tilesW * 0.75 || spanY * 2.0.pow(z) > tilesH * 0.75)) z--
    val cxN = (xs.max() + xs.min()) / 2; val cyN = (ys.max() + ys.min()) / 2
    val lat = Math.toDegrees(atan(sinh(PI * (1 - 2 * cyN))))
    return Triple(lat, cxN * 360 - 180, z)
}
