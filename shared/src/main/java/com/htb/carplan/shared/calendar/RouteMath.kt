package com.htb.carplan.shared.calendar

import com.htb.carplan.shared.model.Place
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Distance maths that works without any API (the fallback while there is no Kakao key). */
object RouteMath {
    private const val EARTH_M = 6_371_000.0

    fun Place.hasCoords() = lat != 0.0 || lng != 0.0

    /** Straight-line distance in meters, or null when either place has no coordinates. */
    fun meters(a: Place, b: Place): Double? {
        if (!a.hasCoords() || !b.hasCoords()) return null
        return metersBetween(a.lat, a.lng, b.lat, b.lng)
    }

    /** Haversine distance in meters between two coordinates. */
    fun metersBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** A rough drive estimate for the fallback: straight line × 1.3 at 40 km/h. */
    fun estimateSeconds(meters: Double): Int = (meters * 1.3 / (40_000.0 / 3600)).toInt()

    /**
     * [동선 최소로 정렬]: the first element stays the start; the rest is reordered so the
     * total cost is smallest. [cost] returns null when two items can't be compared
     * (no coordinates) — those items keep their relative order at the end.
     * n ≤ 9 is solved exactly, bigger lists greedily (nearest neighbour).
     */
    fun <T> shortestOrder(items: List<T>, cost: (T, T) -> Double?): List<T> {
        if (items.size <= 2) return items
        val head = items.first()
        val rest = items.drop(1)
        val (routable, fixed) = rest.partition { cost(head, it) != null }
        if (routable.size <= 1) return items
        val best = if (routable.size <= 8) exact(head, routable, cost) else greedy(head, routable, cost)
        return listOf(head) + best + fixed
    }

    private fun <T> exact(head: T, rest: List<T>, cost: (T, T) -> Double?): List<T> {
        var bestOrder = rest
        var bestCost = Double.MAX_VALUE
        permutations(rest).forEach { p ->
            var c = 0.0
            var prev = head
            for (x in p) {
                c += cost(prev, x) ?: Double.MAX_VALUE / 4
                if (c >= bestCost) break
                prev = x
            }
            if (c < bestCost) {
                bestCost = c
                bestOrder = p
            }
        }
        return bestOrder
    }

    private fun <T> greedy(head: T, rest: List<T>, cost: (T, T) -> Double?): List<T> {
        val left = rest.toMutableList()
        val out = mutableListOf<T>()
        var prev = head
        while (left.isNotEmpty()) {
            val next = left.minByOrNull { cost(prev, it) ?: Double.MAX_VALUE }!!
            left.remove(next)
            out += next
            prev = next
        }
        return out
    }

    private fun <T> permutations(list: List<T>): Sequence<List<T>> = sequence {
        if (list.size <= 1) {
            yield(list)
        } else {
            for (i in list.indices) {
                val rest = list.toMutableList().also { it.removeAt(i) }
                for (p in permutations(rest)) yield(listOf(list[i]) + p)
            }
        }
    }
}
