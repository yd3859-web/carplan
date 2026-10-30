package com.htb.carplan.phone.trip

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

    // 11장 4: the road from the car to my pickup place (Kakao Mobility); refetched once the car moved ~150 m.
    var path by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    var routeLeg by remember { mutableStateOf<Leg?>(null) }
    var pathFrom by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val target = mine?.place?.takeIf { it.hasCoords() && mine.status == PickupStatus.WAITING }
    LaunchedEffect(carPos, target) {
        val c = carPos; val tg = target
        if (c == null || tg == null) { path = emptyList(); routeLeg = null; pathFrom = null; return@LaunchedEffect }
        pathFrom?.let { if (RouteMath.metersBetween(it.first, it.second, c.first, c.second) < 150) return@LaunchedEffect }
        runCatching { kakao.route(Place(name = "차", lat = c.first, lng = c.second), tg) }
            .onSuccess { path = it.path; routeLeg = it.leg; pathFrom = c }
            .onFailure { path = emptyList() }
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
                carPos?.let { add(MapMarker(it.first, it.second, "차", MapMarker.Kind.CAR)) }
                pickups.filter { it.place.hasCoords() }.forEach { add(MapMarker(it.place.lat, it.place.lng, it.name.ifBlank { "일행" }, if (it.uid == me) MapMarker.Kind.ME else MapMarker.Kind.PICKUP)) }
                stops.firstOrNull { it.place.hasCoords() }?.let { add(MapMarker(it.place.lat, it.place.lng, it.place.name, MapMarker.Kind.STOP)) }
            }
            val center = carPos ?: mine?.place?.takeIf { it.hasCoords() }?.let { it.lat to it.lng } ?: markers.firstOrNull()?.let { it.lat to it.lng }
            if (center != null) SimpleMap(center.first, center.second, zoom = 13, markers = markers, path = path)
            else Text("좌표가 있는 장소를 고르면 지도가 떠요 (카카오 키 없이는 이름만 저장됨)", fontSize = 15.sp, color = Palette.TextSoft)
            // 11장 4: time and distance left — the phone's own route when it has one, else the car's ETA.
            val etaText = routeLeg?.let { "차가 약 ${(it.seconds / 60).coerceAtLeast(1)}분 뒤 도착 · ${"%.1f".format(it.meters / 1000.0)} km" }
                ?: mine?.etaSeconds?.takeIf { carPos != null && mine.status == PickupStatus.WAITING }?.let { "차가 약 ${(it / 60).coerceAtLeast(1)}분 뒤 도착" }
            etaText?.let { Text(it, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Teal, modifier = Modifier.padding(top = 6.dp)) }

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
