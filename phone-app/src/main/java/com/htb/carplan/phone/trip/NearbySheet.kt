package com.htb.carplan.phone.trip

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.htb.carplan.phone.ui.CenterSheet
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.shared.calendar.PlaceRank
import com.htb.carplan.shared.calendar.RouteMath
import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.model.Stop
import com.htb.carplan.shared.net.KakaoCategory

/**
 * 칸 근처 맛집·명소 (정원 2026-11-04: "등록된 위치에 따른 맛집·관광명소 추천"). Around one stop of the day: 맛집 / 카페 /
 * 관광지, the group's 또 갈래요·가 봄 first, then nearest, with how far each is from the stop. [다음 칸으로] puts the place
 * right after this stop (not at the end); [지도] opens it in a map app.
 */
@Composable
fun NearbySheet(stop: Stop, vm: TripDayViewModel, canAdd: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var category by remember { mutableStateOf(KakaoCategory.FOOD) }
    var list by remember(category) { mutableStateOf<List<Place>?>(null) }
    var message by remember(category) { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf(setOf<String>()) }
    // Several picks go in one after another in the order pressed (QA 2026-11-04: they used to land in reverse).
    var after by remember { mutableStateOf(stop.id) }
    var memos by remember { mutableStateOf<Map<String, com.htb.carplan.shared.model.PlaceMemo>>(emptyMap()) }
    LaunchedEffect(Unit) { memos = runCatching { vm.memos() }.getOrDefault(emptyMap()) }
    LaunchedEffect(category) {
        if (!kakao.available) { list = emptyList(); message = "추천을 쓸 수 없어요(카카오 키 없음)"; return@LaunchedEffect }
        vm.nearby(stop, category).onSuccess { list = it; if (it.isEmpty()) message = "2km 안에 ${com.htb.carplan.shared.text.Josa.ga(category.label)} 없어요" }
            .onFailure { list = emptyList(); message = "찾지 못했어요 · ${it.message?.take(40)}" }
    }
    CenterSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("${stop.place.name} 근처", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, maxLines = 1)
            Text("또 갈래요·가 본 곳 먼저, 그다음 가까운 순", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(KakaoCategory.FOOD, KakaoCategory.CAFE, KakaoCategory.SIGHT).forEach { k ->
                    FilterChip(selected = category == k, onClick = { category = k }, shape = Corner, label = { Text(k.label, fontSize = 16.sp) })
                }
            }
            HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(top = 8.dp))
            val l = list
            when {
                l == null -> Text("찾는 중…", fontSize = 17.sp, color = Palette.TextSoft, modifier = Modifier.padding(vertical = 20.dp))
                l.isEmpty() -> Text(message ?: "없어요", fontSize = 17.sp, color = Palette.TextSoft, modifier = Modifier.padding(vertical = 20.dp))
                else -> LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    itemsIndexed(l, key = { i, p -> p.kakaoId ?: "$i" }) { i, p ->
                        val m = RouteMath.meters(stop.place, p)?.toInt()
                        val key = p.kakaoId ?: p.name
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.padding(end = 12.dp))
                            Column(Modifier.weight(1f)) {
                                // Tap the name: the Kakao Map page — 평점·메뉴·사진·리뷰 (the API has none of them).
                                Row(Modifier.clickable { openPlacePage(context, p) }, verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextStrong, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                                    Text("  평점·메뉴 ›", fontSize = 13.sp, color = Palette.TextSoft)
                                    PlaceRank.badge(p, memos)?.let { Text("  $it", fontSize = 14.sp, color = Palette.TextStrong, fontWeight = FontWeight.Bold) }
                                }
                                Text(listOfNotNull(p.kindLine(), m?.let { if (it < 1000) "${it}m" else "%.1fkm".format(it / 1000.0) }, p.phone.ifBlank { null }).joinToString("  ·  "),
                                    fontSize = 14.sp, color = Palette.ChipText, maxLines = 1)
                                if (p.address.isNotBlank()) Text(p.address, fontSize = 13.sp, color = Palette.TextSoft, maxLines = 1)
                            }
                            TextButton(onClick = {
                                val uri = android.net.Uri.parse("geo:${p.lat},${p.lng}?q=${p.lat},${p.lng}(${android.net.Uri.encode(p.name)})")
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                            }) { Text("지도", fontSize = 15.sp, color = Palette.TextSoft) }
                            if (canAdd) OutlinedButton(
                                onClick = { vm.addStopAfter(p, after, category.label) { id -> after = id }; added = added + key },
                                enabled = key !in added, shape = Corner, border = BorderStroke(1.dp, Palette.Line),
                            ) { Text(if (key in added) "넣었어요" else "다음 칸으로", fontSize = 15.sp, color = if (key in added) Palette.TextSoft else Palette.Navy, fontWeight = FontWeight.SemiBold) }
                        }
                        HorizontalDivider(color = Palette.Line)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("닫기", fontSize = 16.sp, color = Palette.TextSoft) }
            }
        }
    }
}
