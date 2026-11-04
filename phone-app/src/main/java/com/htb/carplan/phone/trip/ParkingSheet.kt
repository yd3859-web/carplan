package com.htb.carplan.phone.trip

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.htb.carplan.phone.ui.CenterSheet
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.shared.calendar.RouteMath
import com.htb.carplan.shared.data.ChatRepository
import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.net.KakaoCategory
import kotlinx.coroutines.launch

/**
 * 12장 "주차장 찾아봐 주세요" (정원 2026-11-04: 알림을 누르면 바로 근처 주차장, 목적지에서 가까운 순). The parking lots around
 * the place the car is about to reach — measured from that place, not from the phone — nearest first. A passenger
 * can post one to the trip chat (the driver hears about it there) or open it in a map app. One Kakao call per open.
 */
@Composable
fun ParkingSheet(target: Place, groupId: String, eventId: String, date: String, me: String?, shareToChat: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lots by remember(target) { mutableStateOf<List<Pair<Place, Int>>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var posted by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(target) {
        if (!target.hasCoords()) { lots = emptyList(); message = "목적지 위치를 몰라서 찾을 수 없어요"; return@LaunchedEffect }
        if (!kakao.available) { lots = emptyList(); message = "주차장 검색을 쓸 수 없어요"; return@LaunchedEffect }
        val found = runCatching { kakao.searchCategory(KakaoCategory.PARKING, target.lat, target.lng, radiusMeters = 1000, size = 15) }
            .recoverCatching { kakao.searchCategory(KakaoCategory.PARKING, target.lat, target.lng, radiusMeters = 2000, size = 15) }
            .getOrElse { message = "찾지 못했어요 · ${it.message?.take(40)}"; emptyList() }
            .let { if (it.isEmpty()) runCatching { kakao.searchCategory(KakaoCategory.PARKING, target.lat, target.lng, radiusMeters = 2000, size = 15) }.getOrDefault(emptyList()) else it }
        // Nearest to the destination first (Kakao already sorts by distance from it; measured again to show metres).
        lots = found.map { it to (RouteMath.meters(target, it)?.toInt() ?: Int.MAX_VALUE) }.sortedBy { it.second }
        if (found.isEmpty() && message == null) message = "2km 안에 주차장이 없어요"
    }
    CenterSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("주차장 찾기", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
            Text("${target.name.ifBlank { "목적지" }} 근처 · 가까운 순", fontSize = 16.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
            HorizontalDivider(color = Palette.Line)
            val list = lots
            when {
                list == null -> Text("찾는 중…", fontSize = 17.sp, color = Palette.TextSoft, modifier = Modifier.padding(vertical = 20.dp))
                list.isEmpty() -> Text(message ?: "주차장이 없어요", fontSize = 17.sp, color = Palette.TextSoft, modifier = Modifier.padding(vertical = 20.dp))
                else -> LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 520.dp)) {
                    itemsIndexed(list, key = { i, (p, _) -> p.kakaoId ?: "$i" }) { i, (p, m) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.padding(end = 12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextStrong, maxLines = 1)
                                Text(listOf(distanceText(m), p.address).filter { it.isNotBlank() }.joinToString("  ·  "), fontSize = 14.sp, color = Palette.TextSoft, maxLines = 1)
                            }
                            // Open in a map app (길 안내는 운전자 내비, 동승자는 위치 확인용).
                            TextButton(onClick = {
                                val uri = android.net.Uri.parse("geo:${p.lat},${p.lng}?q=${p.lat},${p.lng}(${android.net.Uri.encode(p.name)})")
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure { message = "지도 앱이 없어요" }
                            }) { Text("지도", fontSize = 15.sp, color = Palette.TextSoft) }
                            if (shareToChat && me != null) {
                                val key = p.kakaoId ?: p.name
                                OutlinedButton(
                                    onClick = { scope.launch { runCatching { ChatRepository().sharePlace(groupId, me, p, eventId, date) }.onSuccess { posted = posted + key } } },
                                    enabled = key !in posted, shape = Corner, border = BorderStroke(1.dp, Palette.Line),
                                ) { Text(if (key in posted) "올렸어요" else "채팅에", fontSize = 15.sp, color = if (key in posted) Palette.TextSoft else Palette.Navy, fontWeight = FontWeight.SemiBold) }
                            }
                        }
                        HorizontalDivider(color = Palette.Line)
                    }
                }
            }
            if (list != null && list.isNotEmpty() && message != null) Text(message!!, fontSize = 15.sp, color = Palette.Sunday, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("닫기", fontSize = 16.sp, color = Palette.TextSoft) }
            }
        }
    }
}

private fun distanceText(m: Int) = when {
    m == Int.MAX_VALUE -> ""
    m < 1000 -> "목적지에서 ${m}m"
    else -> "목적지에서 %.1fkm".format(m / 1000.0)
}

private fun Place.hasCoords() = lat != 0.0 || lng != 0.0
