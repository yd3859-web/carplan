package com.htb.carplan.shared.calendar

import com.htb.carplan.shared.model.Place

/**
 * 다트 즉석 코스 (2026-11-10): the 동네 the dart hit, made into one day right there — 관광지 → 점심 → 관광지 → 카페,
 * each the closest to the one before (docs/여행상식.md "명소 사이에 점심·카페"). Places come from a search the caller
 * already made; this only picks and orders them.
 */
object DayCourse {
    fun build(centerLat: Double, centerLng: Double, sights: List<Place>, foods: List<Place>, cafes: List<Place>): List<Place> {
        fun d(a: Pair<Double, Double>, p: Place) = RouteMath.metersBetween(a.first, a.second, p.lat, p.lng)
        fun nearest(from: Pair<Double, Double>, list: List<Place>, not: Set<String>) =
            list.filter { it.hasCoords() && key(it) !in not }.minByOrNull { d(from, it) }
        val out = mutableListOf<Place>()
        val used = mutableSetOf<String>()
        var at = centerLat to centerLng
        for (pool in listOf(sights, foods, sights, cafes)) {
            val p = nearest(at, pool, used) ?: continue
            out += p; used += key(p); at = p.lat to p.lng
        }
        return out
    }

    private fun key(p: Place) = p.kakaoId ?: (p.name + p.lat)
    private fun Place.hasCoords() = lat != 0.0 || lng != 0.0
}
