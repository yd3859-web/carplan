package com.htb.carplan.car

import ai.pleos.playground.navi.constants.TurnType
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.network.httpHeaders
import com.htb.carplan.shared.calendar.RouteMath.hasCoords
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

// Same look as MainActivity (white ground, one blue accent).
private val Bg = Color(0xFFFFFFFF)
private val Ink = Color(0xFF111111)
private val Soft = Color(0xFF8A9096)
private val Line = Color(0xFFE4E6E9)
private val Accent = Color(0xFF0066B3)
private val Panel = Color(0xFFF4F5F7)
private val Corner = RoundedCornerShape(4.dp)

/**
 * 주행 화면 (정원 2026-10-30): while a route is active the pane splits — left half what the navigation is
 * doing (next turn, remaining, a small map with the car and the stops), right half today's rail.
 * The SDK has no map view to embed, so the left half is drawn from the data it gives us.
 */
@Composable
fun DriveView(live: NaviLive, trip: TodayTrip?, restricted: Boolean, stops: @Composable () -> Unit, buttons: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize()) {
        NaviPanel(live, trip, Modifier.weight(1f).fillMaxHeight())
        VerticalDivider(color = Line)
        Column(Modifier.weight(1f).fillMaxHeight().padding(28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("오늘 일정", color = Soft, fontSize = 16.sp, modifier = Modifier.weight(1f))
                if (restricted) Text("주행 중 · 보기만", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Column(Modifier.weight(1f)) { stops() }
            buttons()
        }
    }
}

@Composable
private fun NaviPanel(live: NaviLive, trip: TodayTrip?, modifier: Modifier) {
    Column(modifier.padding(28.dp)) {
        // Next turn: glyph + distance + road, like the navigation's own banner.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(turnGlyph(live.turn), color = Ink, fontSize = 72.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(96.dp))
            Column(Modifier.weight(1f)) {
                Text(live.turnMeters?.let { metersText(it.toInt()) } ?: "—", color = Ink, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                Text(listOfNotNull(turnLabel(live.turn), live.turnText?.takeIf { it.isNotBlank() }).joinToString("  ·  ").ifBlank { "경로 안내 중" }, color = Soft, fontSize = 20.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.height(14.dp))
        CarMap(live, trip, Modifier.weight(1f).fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        val eta = live.remainSeconds?.let { LocalTime.now().plusSeconds(it.toLong()) }?.let { "%02d:%02d".format(it.hour, it.minute) }
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(live.nextName ?: trip?.next?.place?.name ?: "다음 목적지", color = Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    listOfNotNull(live.remainMeters?.let { "남은 ${metersText(it)}" }, live.remainSeconds?.let { "${(it / 60).coerceAtLeast(1)}분" }, eta?.let { "도착 $it" }).joinToString("  ·  ").ifBlank { "내비가 경로를 계산하면 남은 거리가 보여요" },
                    color = Ink, fontSize = 20.sp,
                )
            }
        }
        live.address?.takeIf { it.isNotBlank() }?.let { Text("현재 $it", color = Soft, fontSize = 16.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp)) }
    }
}

/** OSM tiles (3×3 around the car) with the car and today's stops — the same maths as the phone's SimpleMap. */
@Composable
private fun CarMap(live: NaviLive, trip: TodayTrip?, modifier: Modifier) {
    val nextStop = trip?.next
    val next = nextStop?.place?.takeIf { it.hasCoords() }
    val centerLat = live.lat ?: next?.lat
    val centerLng = live.lng ?: next?.lng
    BoxWithConstraints(modifier.clip(Corner).border(1.dp, Line, Corner).background(Panel)) {
        if (centerLat == null || centerLng == null) {
            Text("차 위치를 받으면 지도가 떠요", color = Soft, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
            return@BoxWithConstraints
        }
        val zoom = 13
        val n = 2.0.pow(zoom)
        val cx = (centerLng + 180) / 360 * n
        val cy = (1 - ln(tan(Math.toRadians(centerLat)) + 1 / Math.cos(Math.toRadians(centerLat))) / PI) / 2 * n
        val tx = floor(cx).toInt(); val ty = floor(cy).toInt()
        val density = LocalDensity.current
        val tilePx = with(density) { 256.dp.toPx() }
        val wPx = with(density) { maxWidth.toPx() }; val hPx = with(density) { maxHeight.toPx() }
        val ox = wPx / 2 - (cx - (tx - 1)) * tilePx; val oy = hPx / 2 - (cy - (ty - 1)) * tilePx
        for (dy in -1..1) for (dx in -1..1) {
            val y = ty + dy
            if (y < 0 || y >= n.toInt()) continue
            AsyncImage(
                model = tileRequest(zoom, Math.floorMod(tx + dx, n.toInt()), y),
                contentDescription = null, contentScale = ContentScale.FillBounds,
                modifier = Modifier.offset { IntOffset((ox + (dx + 1) * tilePx).toInt(), (oy + (dy + 1) * tilePx).toInt()) }.size(256.dp),
            )
        }
        @Composable
        fun place(lat: Double, lng: Double, sizeDp: Int, color: Color, label: String?, ring: Boolean = false) {
            val mx = (lng + 180) / 360 * n
            val my = (1 - ln(tan(Math.toRadians(lat)) + 1 / Math.cos(Math.toRadians(lat))) / PI) / 2 * n
            val px = ox + (mx - (tx - 1)) * tilePx; val py = oy + (my - (ty - 1)) * tilePx
            val s = with(density) { sizeDp.dp.toPx() }
            Box(Modifier.offset { IntOffset((px - s / 2).toInt(), (py - s / 2).toInt()) }) {
                Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(if (ring) Bg else color).border(if (ring) 3.dp else 2.dp, if (ring) color else Bg, CircleShape))
                label?.let { Text(it, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.offset(x = sizeDp.dp + 2.dp, y = (-2).dp).background(Color(0xCCFFFFFF)).padding(horizontal = 3.dp)) }
            }
        }
        trip?.stops?.filter { it.place.hasCoords() }?.forEach { s ->
            val isNext = s.id == nextStop?.id
            place(s.place.lat, s.place.lng, if (isNext) 20 else 14, if (s.visited) Soft else if (isNext) Accent else Ink, s.place.name.take(10), ring = !s.visited && !isNext)
        }
        if (live.lat != null && live.lng != null) place(live.lat, live.lng, 22, Accent, null)
        Text(TILE_ATTRIBUTION, fontSize = 10.sp, color = Soft, modifier = Modifier.align(Alignment.BottomEnd).background(Color(0xAAFFFFFF)).padding(horizontal = 3.dp))
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

private fun metersText(m: Int) = if (m >= 1000) "%.1f km".format(m / 1000.0) else "$m m"

private fun turnGlyph(t: TurnType?): String = when (t) {
    null -> "↑"
    TurnType.TURN_LEFT, TurnType.TURN_SHARP_L -> "↰"
    TurnType.TURN_RIGHT, TurnType.TURN_SHARP_R -> "↱"
    TurnType.TURN_KEEP_L, TurnType.TURN_ENTER_IC_L, TurnType.TURN_EXIT_IC_L, TurnType.TURN_ENTER_UNDER_L, TurnType.TURN_ENTER_OVER_L, TurnType.TURN_AVOID_UNDER_L, TurnType.TURN_AVOID_OVER_L -> "↖"
    TurnType.TURN_KEEP_R, TurnType.TURN_ENTER_IC_R, TurnType.TURN_EXIT_IC_R, TurnType.TURN_ENTER_UNDER_R, TurnType.TURN_ENTER_OVER_R, TurnType.TURN_AVOID_UNDER_R, TurnType.TURN_AVOID_OVER_R -> "↗"
    TurnType.TURN_UTURN -> "↶"
    TurnType.TURN_ARRIVE, TurnType.TURN_WAY -> "●"
    else -> "↑"
}

private fun turnLabel(t: TurnType?): String? = when (t) {
    null -> null
    TurnType.TURN_STRAIGHT -> "직진"
    TurnType.TURN_LEFT -> "좌회전"
    TurnType.TURN_RIGHT -> "우회전"
    TurnType.TURN_SHARP_L -> "급좌회전"
    TurnType.TURN_SHARP_R -> "급우회전"
    TurnType.TURN_KEEP_L -> "왼쪽 방향"
    TurnType.TURN_KEEP_R -> "오른쪽 방향"
    TurnType.TURN_UTURN -> "유턴"
    TurnType.TURN_ARRIVE -> "목적지"
    TurnType.TURN_WAY -> "경유지"
    TurnType.TURN_TUNNEL -> "터널"
    TurnType.TURN_TOLL -> "요금소"
    TurnType.TURN_ENTER_SERVICEAREA, TurnType.TURN_RESTAREA -> "휴게소"
    else -> t.name.removePrefix("TURN_").lowercase().replace('_', ' ')
}
