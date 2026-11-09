package com.htb.carplan.shared.calendar

/**
 * D7 기억장치 (정원 2026-11-09 "여행 계획 데이터를 모아 문서화해서 기억장치로"): 지역별 대표 코스.
 * 원본은 `docs/여행상식.md` 규칙 + `assets/courses.txt`(지금은 손으로 정리, TourAPI 키가 오면
 * `tools/fetch_courses.py`가 공공데이터로 다시 만든다). 추천(AI·대체 둘 다)이 이 코스를 바탕으로 골라서,
 * 지어낸 장소가 아니라 실재하는 코스에서 출발한다.
 */
data class Course(val region: String, val kinds: List<String>, val places: List<String>, val note: String = "") {
    /** 검색어 머리: "담양군" → "담양" (카카오는 접미사 없는 쪽이 잘 찾는다). */
    val word: String get() = region.removeSuffix("특별자치시").let { r -> listOf("시", "군", "구").firstOrNull { r.length > 2 && r.endsWith(it) }?.let { r.dropLast(1) } ?: r }
}

object CourseBook {

    /** `지역|태그,태그|장소;장소;…|설명` 한 줄 = 한 동네. '#'·빈 줄은 무시. */
    fun parse(text: String): List<Course> = text.lineSequence()
        .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val p = line.split('|')
            if (p.size < 3) return@mapNotNull null
            Course(
                region = p[0].trim(),
                kinds = p[1].split(',').map { it.trim() }.filter { it.isNotEmpty() },
                places = p[2].split(';').map { it.trim() }.filter { it.isNotEmpty() },
                note = p.getOrNull(3)?.trim().orEmpty(),
            ).takeIf { it.region.isNotEmpty() && it.places.size >= 2 }
        }.toList()

    /**
     * 후보 동네 [n]곳: 가 본 지역([taste].regions)과 [avoid]를 빼고, 취향 종류가 겹치는 순.
     * 겹침이 같으면 파일 순서(전국을 섞어 둔 순서) 그대로.
     */
    fun candidates(all: List<Course>, taste: Taste, avoid: List<String>, n: Int = 6): List<Course> {
        val been = taste.regions.map { it.first }.toSet()
        val liked = taste.kinds.map { it.first }
        fun score(c: Course) = c.kinds.sumOf { k -> val i = liked.indexOf(k); if (i < 0) 0 else (liked.size - i).toInt() }
        return all.filter { it.region !in been && it.region !in avoid && avoid.none { a -> it.region.startsWith(a) || a.startsWith(it.word) } }
            .sortedByDescending(::score).distinctBy { it.region }.take(n)
    }

    /** AI 프롬프트에 끼우는 줄들: "담양군: 죽녹원 → 관방제림 → … (대나무숲…)". */
    fun promptLines(cs: List<Course>): String =
        cs.joinToString("\n") { c -> "- ${c.region}: ${c.places.joinToString(" → ")}" + (c.note.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") }
}
