package com.htb.carplan.phone.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htb.carplan.phone.ui.CenterSheet
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.MapMarker
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.phone.ui.SimpleMap
import com.htb.carplan.shared.calendar.RouteMath
import com.htb.carplan.shared.calendar.RouteMath.hasCoords
import com.htb.carplan.shared.model.PickupStatus
import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.net.Leg

/**
 * 11장 픽업: my pickup place, everyone's order and status, and a map with the car coming
 * (the car dot moves only while the driver turned sharing on, Q27).
 */
@Composable
fun PickupSheet(vm: TripDayViewModel, onDismiss: () -> Unit) {
    val pickups by vm.pickups.collectAsStateWithLifecycle()
    val me by vm.uid.collectAsStateWithLifecycle()
    val car by vm.tripCar.collectAsStateWithLifecycle()
    val day by vm.day.collectAsStateWithLifecycle()
    val stops by vm.stops.collectAsStateWithLifecycle()
    vm.names.collectAsStateWithLifecycle().value
    var searching by remember { mutableStateOf(false) }
    val mine = pickups.firstOrNull { it.uid == me }
    val sharing = day?.pickupSharing == true
    val carPos = car?.state?.let { s -> if (sharing && s.lat != null && s.lng != null) s.lat!! to s.lng!! else null }

    // 11장 4: the road from the car to my pickup place (Kakao Mobility — the phone has no Hyundai navigation, and the
    // car's own route line is not available from the SDK). Fetched once when the car sets off and kept, so the friend
    // sees the whole way and how much of it is done; fetched again only when the car leaves it (a detour).
    var route by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    var routeLeg by remember { mutableStateOf<Leg?>(null) }
    var fetchedAt by remember { mutableStateOf(0L) }
    val target = mine?.place?.takeIf { it.hasCoords() && mine.status == PickupStatus.WAITING }
    LaunchedEffect(carPos, target) {
        val c = carPos; val tg = target
        if (c == null || tg == null) { route = emptyList(); routeLeg = null; return@LaunchedEffect }
        val off = route.takeIf { it.size >= 2 }?.let { r -> r.minOf { RouteMath.metersBetween(it.first, it.second, c.first, c.second) } }
        if (off != null && (off < OFF_ROUTE_M || System.currentTimeMillis() - fetchedAt < REFETCH_MS)) return@LaunchedEffect
        runCatching { kakao.route(Place(name = "차", lat = c.first, lng = c.second), tg) }
            .onSuccess { route = it.path; routeLeg = it.leg; fetchedAt = System.currentTimeMillis() }
    }
    // Where the car is on that road: driven part, the rest, and the direction it points.
    val progress = remember(route, carPos) { carPos?.let { routeProgress(route, it) } }

    // 카카오T처럼 (정원 11/2): while the car is coming for me, the whole screen is the map with the car gliding along.
    var showSheet by remember { mutableStateOf(false) }
    if (carPos != null && target != null && mine.status == PickupStatus.WAITING && !showSheet) {
        val driver = car?.ownerUid?.let { vm.names.value[it] }?.ifBlank { null } ?: "운전자"
        PickupLiveScreen(
            route = route, carPos = carPos, pickup = target, driver = driver, etaSeconds = mine.etaSeconds, arrived = mine.arrivedAt != null,
            onBoarded = vm::boarded, onDetails = { showSheet = true }, onBack = onDismiss,
        )
        return
    }

    CenterSheet(onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("픽업", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
            Text(
                when {
                    pickups.isEmpty() -> "태우러 올 곳을 정하면 차가 출발할 때 경유지 맨 앞에 들어가요."
                    sharing -> "차가 오는 중 · 위치를 5초마다 보여 줘요"
                    else -> "차가 출발하면 운전자에게 위치 공유를 물어봐요"
                },
                fontSize = 15.sp, color = if (sharing) Palette.Teal else Palette.TextSoft, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
            val markers = buildList {
                carPos?.let { add(MapMarker(it.first, it.second, "", MapMarker.Kind.CAR, bearing = progress?.bearing)) }
                pickups.filter { it.place.hasCoords() }.forEach { add(MapMarker(it.place.lat, it.place.lng, it.name.ifBlank { "일행" }, if (it.uid == me) MapMarker.Kind.ME else MapMarker.Kind.PICKUP)) }
                stops.firstOrNull { it.place.hasCoords() }?.let { add(MapMarker(it.place.lat, it.place.lng, it.place.name, MapMarker.Kind.STOP)) }
            }
            val center = carPos ?: mine?.place?.takeIf { it.hasCoords() }?.let { it.lat to it.lng } ?: markers.firstOrNull()?.let { it.lat to it.lng }
            // While the car comes: frame the car and my pickup place together; otherwise around the places.
            val fit = listOfNotNull(carPos, target?.let { it.lat to it.lng }).takeIf { it.size == 2 }.orEmpty()
            if (center != null) SimpleMap(center.first, center.second, zoom = 13, markers = markers, path = progress?.ahead ?: route, passed = progress?.behind.orEmpty(), fit = fit)
            else Text("태울 곳을 고르면 지도가 떠요", fontSize = 15.sp, color = Palette.TextSoft)
            // 11장 4: time left = the car's own ETA (the same number the car shows, every 30 s); the phone's route only fills
            // in the distance, and the time when the car has none yet (P4).
            val carEta = mine?.etaSeconds?.takeIf { carPos != null && mine.status == PickupStatus.WAITING }
            val left = progress?.aheadMeters ?: routeLeg?.meters?.toDouble()
            val arrived = mine?.arrivedAt != null && mine.status == PickupStatus.WAITING
            val etaText = if (arrived) "차가 도착했어요" else (carEta ?: routeLeg?.seconds?.toLong())?.let { sec ->
                "차가 약 ${(sec / 60).coerceAtLeast(1)}분 뒤 도착" + (left?.let { " · 남은 ${"%.1f".format(it / 1000.0)} km" } ?: "")
            }
            etaText?.let { Text(it, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.Teal, modifier = Modifier.padding(top = 8.dp)) }
            // How far the car has come: a bar over the whole road (since it set off) and the numbers under it.
            progress?.takeIf { it.totalMeters > 0 && target != null }?.let { pr ->
                val done = (pr.behindMeters / pr.totalMeters).toFloat().coerceIn(0f, 1f)
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp).background(Palette.Line, Corner)) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(done).height(6.dp).background(Palette.Teal, Corner))
                }
                Text("${"%.1f".format(pr.totalMeters / 1000.0)} km 중 ${"%.1f".format(pr.behindMeters / 1000.0)} km 왔어요", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 4.dp))
            }

            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("내 픽업 장소", fontSize = 15.sp, color = Palette.TextSoft)
                    Text(mine?.place?.name ?: "아직 없음", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
                    mine?.let { Text(statusText(it.status, it.arrivedAt != null), fontSize = 15.sp, color = Palette.Teal) }
                }
                if (mine == null) Button(onClick = { searching = true }, shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy)) { Text("장소 고르기") }
                else {
                    if (mine.status == PickupStatus.WAITING) Button(onClick = vm::boarded, shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy)) { Text("탔어요") }
                    TextButton(onClick = { searching = true }) { Text("바꾸기", color = Palette.TextSoft) }
                    TextButton(onClick = vm::removeMyPickup) { Text("빼기", color = Palette.TextSoft) }
                }
            }
            HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(vertical = 10.dp))
            Text("픽업 순서", fontSize = 15.sp, color = Palette.TextSoft)
            pickups.forEachIndexed { i, p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Teal, modifier = Modifier.padding(end = 10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${p.name.ifBlank { "일행" }} · ${p.place.name}", fontSize = 17.sp, color = Palette.TextStrong)
                        Text(statusText(p.status, p.arrivedAt != null), fontSize = 15.sp, color = Palette.TextSoft)
                    }
                    if (i > 0) TextButton(onClick = { vm.setPickupOrder(pickups.map { it.uid }.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) }) }) { Text("위로", color = Palette.TextSoft, fontSize = 15.sp) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) { TextButton(onClick = onDismiss) { Text("닫기", color = Palette.TextSoft) } }
        }
    }
    if (searching) PlaceSearchSheet(vm = vm, initialQuery = mine?.place?.name.orEmpty(), onPick = { vm.setMyPickup(it); searching = false }, onDismiss = { searching = false })
}

private fun statusText(s: PickupStatus, arrived: Boolean) = when {
    s == PickupStatus.ON_BOARD -> "탔어요"
    s == PickupStatus.DONE -> "끝"
    arrived -> "차 도착 · 탔으면 눌러 주세요"
    else -> "기다리는 중"
}

private const val OFF_ROUTE_M = 300.0
private const val REFETCH_MS = 30_000L

/** The car on its road: [behind] = driven (start → car), [ahead] = car → pickup, lengths in meters, [bearing] in degrees. */
private class RouteProgress(val behind: List<Pair<Double, Double>>, val ahead: List<Pair<Double, Double>>, val behindMeters: Double, val aheadMeters: Double, val bearing: Double?) {
    val totalMeters get() = behindMeters + aheadMeters
}

private fun routeProgress(route: List<Pair<Double, Double>>, car: Pair<Double, Double>): RouteProgress? {
    if (route.size < 2) return null
    fun m(a: Pair<Double, Double>, b: Pair<Double, Double>) = RouteMath.metersBetween(a.first, a.second, b.first, b.second)
    val i = route.indices.minBy { m(route[it], car) }
    val behind = route.subList(0, i + 1) + listOf(car)
    val ahead = listOf(car) + route.subList((i + 1).coerceAtMost(route.size), route.size)
    fun len(p: List<Pair<Double, Double>>) = p.zipWithNext().sumOf { (a, b) -> m(a, b) }
    // Direction: along the road just ahead of the car (the car reports no heading).
    val from = route[i]; val to = route.getOrNull(i + 1) ?: route[i]
    val bearing = if (from == to) null else run {
        val y = Math.sin(Math.toRadians(to.second - from.second)) * Math.cos(Math.toRadians(to.first))
        val x = Math.cos(Math.toRadians(from.first)) * Math.sin(Math.toRadians(to.first)) - Math.sin(Math.toRadians(from.first)) * Math.cos(Math.toRadians(to.first)) * Math.cos(Math.toRadians(to.second - from.second))
        (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
    }
    return RouteProgress(behind, ahead, len(behind), len(ahead), bearing)
}
