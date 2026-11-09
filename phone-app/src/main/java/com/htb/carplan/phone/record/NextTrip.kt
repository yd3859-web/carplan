package com.htb.carplan.phone.record

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.functions.FirebaseFunctions
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.shared.calendar.TasteBuild
import com.htb.carplan.shared.calendar.TastePlace
import com.htb.carplan.shared.calendar.TasteTrip
import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.net.KakaoApi
import com.htb.carplan.shared.net.KakaoCategory
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * D 다음 여행 추천 (정원 2026-11-06 "다녀온 여행지·이동경로 기반으로 다음 여행 장소 추천 — 취향 기반이 제일 중요"):
 * 기록으로 취향을 정리하고(D1, [TasteBuild] — 우리 데이터·우리 계산), AI(D4, 서버 suggestTrip)는 안 가 본 동네와
 * 하루 동선을 골라 말로 풀어 준다. 받은 장소는 카카오로 실제 있는지 확인한 것만. AI가 없으면(키 전·실패)
 * "또 갈래요 누른 곳 근처 다시 가기"로 대신한다(D5). 추천은 저장해 뒀다가 먼저 보여 준다(시연장 인터넷 대비).
 */
data class NextTrip(
    val region: String,
    val title: String,
    val reason: String,
    val stops: List<Place>,
    val fromAi: Boolean,
    /** 만들 날: 다가오는 토요일. */
    val date: String,
)

sealed interface NextTripState {
    data object Idle : NextTripState
    data object Loading : NextTripState
    data class Ready(val trip: NextTrip) : NextTripState
    data class Off(val why: String) : NextTripState
}

object NextTripLoader {

    fun nextSaturday(): String = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.SATURDAY)).toString()

    /** 기록 책 → 취향 요약 글 (장소 이름·지역·숫자만 — 메모·사진·사람 이름은 안 보냄). */
    fun tasteText(books: List<DiaryBook>): String {
        val trips = books.map { b ->
            TasteTrip(
                days = b.pages.map { (_, rs) -> rs.map { r -> TastePlace(r.place.name, r.place.category, r.place.address, r.place.lat, r.place.lng, again = r.againBy.size) } },
                members = b.memberIds.size.coerceAtLeast(1),
            )
        }
        return TasteBuild.promptText(TasteBuild.from(trips))
    }

    suspend fun load(books: List<DiaryBook>, kakao: KakaoApi, avoid: List<String>): NextTrip? =
        fromAi(tasteText(books), kakao, avoid) ?: fallback(books, kakao)

    /** 서버 suggestTrip → 카카오 확인. Null = 키 없음·실패·장소 확인 2곳 미만. */
    private suspend fun fromAi(taste: String, kakao: KakaoApi, avoid: List<String>): NextTrip? = runCatching {
        val res = FirebaseFunctions.getInstance("asia-northeast3").getHttpsCallable("suggestTrip")
            .call(mapOf("taste" to taste, "avoid" to avoid.joinToString(", "))).await()
        val m = res.data as? Map<*, *> ?: return null
        val region = m["region"] as? String ?: return null
        val raw = (m["stops"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val stops = raw.mapNotNull { st ->
            val name = st["name"] as? String ?: return@mapNotNull null
            val query = (st["query"] as? String).orEmpty().ifBlank { name }
            if (kakao.available) runCatching { kakao.searchKeyword(query, size = 1).firstOrNull() }.getOrNull()
            else Place(name = name) // 키 없는 컴퓨터: 이름만 — 차 내비 검색으로 찾는 길이 이미 있음
        }
        if (kakao.available && stops.size < 2) return null // 지어낸 장소 방지: 확인된 곳이 2곳은 돼야
        NextTrip(region, m["title"] as? String ?: "$region 하루", m["reason"] as? String ?: "", stops, fromAi = true, date = nextSaturday())
    }.onFailure { android.util.Log.w("NextTrip", "AI 추천 실패 — 대체 추천으로", it) }.getOrNull()

    /** AI 없이: 또 갈래요(없으면 마지막 기록) 근처 다시 가기 + 그 둘레 명소·맛집 한 곳씩. */
    private suspend fun fallback(books: List<DiaryBook>, kakao: KakaoApi): NextTrip? {
        val all = books.flatMap { b -> b.pages.flatMap { it.second } }
        val base = (all.filter { it.againBy.isNotEmpty() }.sortedByDescending { it.againBy.size } + all.reversed())
            .firstOrNull { it.place.lat != 0.0 || it.place.lng != 0.0 } ?: return null
        val p = base.place
        val extra = if (kakao.available) runCatching {
            kakao.searchCategory(KakaoCategory.SIGHT, p.lat, p.lng, 10_000, 3).filterNot { it.name == p.name }.take(1) +
                kakao.searchCategory(KakaoCategory.FOOD, p.lat, p.lng, 5_000, 3).take(1)
        }.getOrDefault(emptyList()) else emptyList()
        val region = TasteBuild.region(p.address) ?: p.name
        val reason = if (base.againBy.isNotEmpty()) "${p.name}에 또 갈래요를 ${base.againBy.size}명이 눌렀어요" else "좋았던 ${p.name} 근처를 다시 돌아봐요"
        return NextTrip(region, "$region 다시 가기", reason, listOf(p) + extra, fromAi = false, date = nextSaturday())
    }
}

/** 마지막 추천을 저장해 두고 다음에 먼저 보여 준다 (D5 시연 안전장치). */
object NextTripStore {
    private const val PREF = "next_trip"

    fun save(context: Context, t: NextTrip) {
        val o = JSONObject().put("region", t.region).put("title", t.title).put("reason", t.reason)
            .put("fromAi", t.fromAi).put("date", t.date)
            .put("stops", JSONArray(t.stops.map { p ->
                JSONObject().put("name", p.name).put("lat", p.lat).put("lng", p.lng).put("address", p.address)
                    .put("category", p.category).put("phone", p.phone).put("kakaoId", p.kakaoId ?: "")
            }))
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("t", o.toString()).apply()
    }

    fun load(context: Context): NextTrip? = runCatching {
        val s = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("t", null) ?: return null
        val o = JSONObject(s)
        val stops = (0 until o.getJSONArray("stops").length()).map { i ->
            val p = o.getJSONArray("stops").getJSONObject(i)
            Place(p.getString("name"), p.getDouble("lat"), p.getDouble("lng"), p.optString("address"),
                p.optString("kakaoId").ifBlank { null }, p.optString("category"), p.optString("phone"))
        }
        // 지난 토요일 추천이면 날짜만 다가오는 토요일로 당겨 쓴다.
        NextTrip(o.getString("region"), o.getString("title"), o.getString("reason"), stops,
            o.optBoolean("fromAi"), maxOf(o.optString("date"), NextTripLoader.nextSaturday()))
    }.getOrNull()
}

/** 기록 탭 끝 "다음엔 여기 어때요?" — 흰 바탕·구분선·4dp, 파랑은 장소에만 (디자인 규칙). */
@Composable
fun NextTripCard(state: NextTripState, onMake: (NextTrip) -> Unit, onOther: () -> Unit) {
    if (state is NextTripState.Idle) return
    Column(Modifier.fillMaxWidth().padding(top = 30.dp)) {
        HorizontalDivider(color = Palette.Line)
        Text("다음엔 여기 어때요?", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.padding(top = 16.dp))
        when (state) {
            is NextTripState.Loading -> Text("기록을 읽고 고르는 중…", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 6.dp))
            is NextTripState.Off -> Text(state.why, fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 6.dp))
            is NextTripState.Ready -> {
                val t = state.trip
                Text(t.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Teal, modifier = Modifier.padding(top = 10.dp))
                if (t.reason.isNotBlank()) Text(t.reason, fontSize = 15.sp, color = Palette.TextStrong, modifier = Modifier.padding(top = 4.dp))
                t.stops.forEachIndexed { i, p ->
                    Row(Modifier.fillMaxWidth().padding(top = if (i == 0) 10.dp else 6.dp)) {
                        Text("${i + 1}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.padding(end = 10.dp))
                        Column {
                            Text(p.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextStrong, maxLines = 1)
                            val sub = listOfNotNull(p.kindLineOrNull(), p.address.ifBlank { null }).joinToString("  ·  ")
                            if (sub.isNotBlank()) Text(sub, fontSize = 13.sp, color = Palette.TextSoft, maxLines = 1)
                        }
                    }
                }
                Text(if (t.fromAi) "기록으로 정리한 취향 → AI가 고름 · 장소는 카카오로 확인했어요" else "기록만으로 고른 추천이에요",
                    fontSize = 13.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onMake(t) }, shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy),
                        modifier = Modifier.weight(1f).height(48.dp)) {
                        Text("이 여행 만들기 · ${t.date.takeLast(5).replace('-', '/')} 토", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(onClick = onOther, shape = Corner, border = BorderStroke(1.dp, Palette.Line), modifier = Modifier.height(48.dp)) {
                        Text("다른 곳", fontSize = 15.sp, color = Palette.TextStrong)
                    }
                }
            }
            else -> {}
        }
    }
}

/** 업종 한 줄 ("음식점 > 한식 > 국밥" → "한식 · 국밥") — PlaceInfo.kindLine()과 같은 생각, 빈 값이면 null. */
private fun Place.kindLineOrNull(): String? = category.split(">").map { it.trim() }.filter { it.isNotBlank() }
    .drop(1).takeIf { it.isNotEmpty() }?.joinToString(" · ")
