package com.htb.carplan.shared.calendar

import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.model.PlaceMemo

/**
 * Q58: recommendation order — places my groups want to visit again first (more presses first), then places the car
 * has stopped at ("가 봄"), then the rest as Kakao gave them (nearest first). Places without a Kakao id keep their spot.
 */
object PlaceRank {
    fun rank(places: List<Place>, memos: Map<String, PlaceMemo>): List<Place> =
        places.withIndex().sortedWith(compareBy({ -(memo(it.value, memos)?.againBy?.size ?: 0) }, { if (memo(it.value, memos)?.visited == true) 0 else 1 }, { it.index }))
            .map { it.value }

    /** The small label next to a recommended place: "또 갈래요 2" / "가 봄", or null. */
    fun badge(place: Place, memos: Map<String, PlaceMemo>): String? {
        val m = memo(place, memos) ?: return null
        return when {
            m.againBy.isNotEmpty() -> "또 갈래요 ${m.againBy.size}"
            m.visited -> "가 봄"
            else -> null
        }
    }

    private fun memo(p: Place, memos: Map<String, PlaceMemo>): PlaceMemo? =
        p.kakaoId?.takeIf { it.isNotBlank() }?.let { memos[it] } ?: nameKey(p)?.let { memos[it] }

    /**
     * The memo id of a place: its Kakao id, or for a place typed by name (demo data, 계획 밖 칸 without a match) its
     * name plus where it is to about 100 m — so a Kakao result of the same place still finds it. Null = cannot tell.
     */
    fun keyOf(p: Place): String? = p.kakaoId?.takeIf { it.isNotBlank() } ?: nameKey(p)

    private fun nameKey(p: Place): String? {
        if (p.name.isBlank() || (p.lat == 0.0 && p.lng == 0.0)) return null
        return "n:" + p.name.trim().replace("/", "_") + "@" + "%.3f,%.3f".format(java.util.Locale.ROOT, p.lat, p.lng)
    }
}
