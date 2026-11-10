package com.htb.carplan.shared.calendar

import com.htb.carplan.shared.model.NaviPlace
import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.model.PlaceMemo

/**
 * A2·A3 (2026-11-10): the places we already know, listed before any Kakao result in the phone's place picker —
 * the car navigation's 즐겨찾기·최근 목적지 ("우리 차 내비") and the places our cars parked at ("가 본 곳").
 * No API: names come from the navigation and from our own records.
 */
object OurPlaces {
    data class Item(val place: Place, val label: String, val navi: Boolean)

    /** Same place when the names match and the spots are within this many meters (or one has no spot). */
    private const val SAME_METERS = 300.0

    /**
     * Blank [query]: the navigation's places (bookmarks first, as the car gave them) then visited places, newest first,
     * [limit] of each. Otherwise: those whose name or address holds the query (spaces ignored).
     */
    fun list(navi: List<NaviPlace>, memos: Collection<PlaceMemo>, query: String = "", limit: Int = 5): List<Item> {
        val q = norm(query)
        fun hit(name: String, address: String) = q.isEmpty() || norm(name).contains(q) || norm(address).contains(q)
        val visited = memos.filter { it.visited && it.name.isNotBlank() && hit(it.name, it.address) }
            .sortedWith(compareByDescending<PlaceMemo> { it.againBy.size }.thenByDescending { it.lastAt?.toDate()?.time ?: 0L })
        val fromNavi = navi.filter { it.usable && hit(it.place.name, it.address) }
            .sortedBy { if (it.kind == null) 1 else 0 }
            .distinctBy { norm(it.place.name) }
            .take(limit)
            .map { n ->
                // The same place in our records adds what the car learned there.
                val m = visited.firstOrNull { same(it.place, n.place) }
                Item(n.place, listOfNotNull("우리 차 · " + n.kindLabel, m?.let { memoLine(it) }).joinToString(" · "), navi = true)
            }
        val fromRecords = visited.filter { m -> fromNavi.none { same(it.place, m.place) } }.take(limit)
            .map { Item(it.place, memoLine(it), navi = false) }
        return fromNavi + fromRecords
    }

    /** "또 갈래요 2 · 가 봄 3번 · 주차 B2 · 1시간 머묾". */
    fun memoLine(m: PlaceMemo): String = listOfNotNull(
        m.againBy.size.takeIf { it > 0 }?.let { "또 갈래요 $it" },
        if (m.visits > 1) "가 봄 ${m.visits}번" else "가 봄",
        m.parking.takeIf { it.isNotBlank() }?.let { "주차 $it" },
        m.stayMinutes?.takeIf { it > 0 }?.let { stay(it) },
    ).joinToString(" · ")

    private fun stay(min: Long): String = when {
        min < 60 -> "${min}분 머묾"
        min % 60 == 0L -> "${min / 60}시간 머묾"
        else -> "${min / 60}시간 ${min % 60}분 머묾"
    }

    private fun same(a: Place, b: Place): Boolean {
        if (norm(a.name) != norm(b.name)) return false
        val noSpot = (a.lat == 0.0 && a.lng == 0.0) || (b.lat == 0.0 && b.lng == 0.0)
        return noSpot || RouteMath.metersBetween(a.lat, a.lng, b.lat, b.lng) <= SAME_METERS
    }

    private fun norm(s: String) = s.replace(" ", "").lowercase()
}
