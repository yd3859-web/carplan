package com.htb.carplan.shared.calendar

import kotlin.math.roundToInt

/**
 * D1 취향 정리 (정원 2026-11-06, "다음 여행 추천"): what the group's own records say this user likes — the raw
 * material the AI (D4) gets instead of the records themselves (no names, no notes, no photos). Pure Kotlin so
 * both apps and the tests can use it.
 */

/** One visited place as the taste sums see it (mapped from a [Record]; nothing personal). */
data class TastePlace(
    val name: String,
    val category: String = "",
    val address: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    /** How many people pressed [또 갈래요] on this record. */
    val again: Int = 0,
)

/** One past trip: its days (each day's places in visiting order) and how many people went. */
data class TasteTrip(val days: List<List<TastePlace>>, val members: Int = 1)

/** The summary itself: what kind of places, where, how far in a day, with whom. */
data class Taste(
    val tripCount: Int,
    /** ("바다", 40) … percent of the places that could be sorted, 많이 간 순. */
    val kinds: List<Pair<String, Int>>,
    /** "송도활어회센터(포항시)" … 또 갈래요 많이 받은 순. */
    val again: List<String>,
    /** ("포항시", 3) … 가 본 시·군, 많이 간 순. */
    val regions: List<Pair<String, Int>>,
    /** 하루에 움직인 거리(도로 느낌으로 보정한 직선 합)의 중앙값, km. Null = 좌표가 모자람. */
    val typicalKm: Int?,
    /** "주로 3명이 같이" / "주로 혼자". */
    val companions: String,
)

object TasteBuild {

    /** 종류 버킷: 이름+업종 글자로 고른다 (카카오 업종이 없을 때도 이름으로). Null = 모르겠음. */
    fun kindOf(name: String, category: String = ""): String? {
        val s = "$name $category"
        fun has(vararg words: String) = words.any { it in s }
        return when {
            has("캠핑", "야영", "글램핑", "카라반") -> "캠핑"
            has("카페", "커피", "베이커리", "디저트") -> "카페"
            "음식점" in category || has("식당", "맛집", "횟집", "국밥", "회센터") -> "맛집"
            has("해수욕장", "해변", "바다", "항구", "포구", "등대", "방파제", "해안") ||
                (name.length >= 3 && name.endsWith("항") && !name.endsWith("공항")) -> "바다"
            has("놀이", "테마파크", "워터", "아쿠아리움", "동물원", "키즈") -> "놀이"
            has("박물관", "미술관", "전시", "유적", "한옥", "민속", "과학관") -> "문화"
            has("산 ", "수목원", "휴양림", "공원", "계곡", "폭포", "호수", "숲", "정원", "섬") || name.endsWith("산") -> "자연"
            else -> null
        }
    }

    private val metro = listOf("서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종")

    /** 주소에서 시·군 하나: "경북 포항시 북구 …" → "포항시", "서울 강남구 …" → "서울". Null = 주소 없음. */
    fun region(address: String): String? {
        val toks = address.split(' ').filter { it.isNotBlank() }
        if (toks.isEmpty()) return null
        toks.drop(1).forEach { t -> if (t.length >= 2 && (t.endsWith("시") || t.endsWith("군"))) return t }
        val first = toks[0].removeSuffix("특별시").removeSuffix("광역시").removeSuffix("특별자치시")
        if (first in metro) return first
        toks.drop(1).forEach { t -> if (t.length >= 2 && t.endsWith("구")) return t }
        return null
    }

    /** 하루 이동 거리: 그날 들른 곳 사이 직선 합 × 1.3 (도로 보정), km. */
    private fun dayKm(day: List<TastePlace>): Double? {
        val pts = day.filter { it.lat != 0.0 || it.lng != 0.0 }
        if (pts.size < 2) return null
        return pts.zipWithNext().sumOf { (a, b) -> RouteMath.metersBetween(a.lat, a.lng, b.lat, b.lng) } * 1.3 / 1000.0
    }

    fun from(trips: List<TasteTrip>): Taste {
        val places = trips.flatMap { it.days.flatten() }
        val kindCounts = places.mapNotNull { kindOf(it.name, it.category) }.groupingBy { it }.eachCount()
        val kinds = kindCounts.entries.sortedByDescending { it.value }.take(4)
            .map { it.key to (it.value * 100.0 / kindCounts.values.sum()).roundToInt() }
        val again = places.filter { it.again > 0 }.sortedByDescending { it.again }.distinctBy { it.name }
            .take(5).map { p -> p.name + (region(p.address)?.let { "($it)" } ?: "") }
        val regions = places.mapNotNull { region(it.address) }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(8).map { it.key to it.value }
        val kms = trips.flatMap { it.days }.mapNotNull(::dayKm).sorted()
        val typicalKm = kms.takeIf { it.isNotEmpty() }?.let { it[it.size / 2].roundToInt() }
        val avg = trips.map { it.members }.takeIf { it.isNotEmpty() }?.average() ?: 1.0
        val companions = if (avg < 1.5) "주로 혼자" else "주로 ${avg.roundToInt()}명이 같이"
        return Taste(trips.size, kinds, again, regions, typicalKm, companions)
    }

    /** AI에 보내는 요약 글 (개인정보 없음 — 장소 이름·지역·숫자만). */
    fun promptText(t: Taste): String = buildString {
        appendLine("차로 다녀온 여행 ${t.tripCount}번의 기록 요약:")
        if (t.kinds.isNotEmpty()) appendLine("- 자주 간 종류: " + t.kinds.joinToString(", ") { "${it.first} ${it.second}%" })
        if (t.again.isNotEmpty()) appendLine("- 또 가고 싶다고 한 곳: " + t.again.joinToString(", "))
        if (t.regions.isNotEmpty()) appendLine("- 가 본 지역: " + t.regions.joinToString(", ") { "${it.first} ${it.second}번" })
        t.typicalKm?.let { appendLine("- 하루 이동 거리: 보통 ${it}km 정도") }
        appendLine("- ${t.companions} 다님")
    }.trim()
}
