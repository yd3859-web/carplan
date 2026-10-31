package com.htb.carplan.shared.calendar

import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.model.Stop

/**
 * 12장 Q55 (가벼운 판, 2026-10-31): before setting off, how far the car gets on what is in the tank, measured
 * along the day's stops — and if that is short of the last stop, where on the way to fill up.
 * Nothing here calls a network; the car passes in what it knows (fuel %, range, road distances) and gets back
 * a plain answer it can say out loud.
 */
object FuelPlan {
    /** One leg of the day: from one place to the next, by road when known. */
    data class Leg(val from: Place, val to: Stop, val meters: Double)

    /**
     * @param reachMeters how far the car can go now (range from the car; or fuel% × km-per-% when there is no range).
     * @param marginMeters keep this much in reserve (Q29: 30 km).
     * @return null when the tank covers the whole day; else the leg during which it runs short and the point
     *         (metres from the start of the day) where filling up should happen by.
     */
    fun shortfall(legs: List<Leg>, reachMeters: Double, marginMeters: Double): Shortfall? {
        var run = 0.0
        val limit = reachMeters - marginMeters
        for ((i, leg) in legs.withIndex()) {
            if (run + leg.meters > limit) return Shortfall(legIndex = i, byMeters = maxOf(0.0, limit), total = legs.sumOf { it.meters })
            run += leg.meters
        }
        return null
    }

    data class Shortfall(val legIndex: Int, val byMeters: Double, val total: Double)

    /** A point along the legs [meters] from the start (straight-line interpolation inside a leg). */
    fun pointAt(legs: List<Leg>, meters: Double): Place? {
        var run = 0.0
        for (leg in legs) {
            if (meters <= run + leg.meters) {
                val f = if (leg.meters <= 0) 0.0 else ((meters - run) / leg.meters).coerceIn(0.0, 1.0)
                return Place(name = "", lat = leg.from.lat + (leg.to.place.lat - leg.from.lat) * f, lng = leg.from.lng + (leg.to.place.lng - leg.from.lng) * f)
            }
            run += leg.meters
        }
        return legs.lastOrNull()?.to?.place
    }

    /** "율동공원 지나서" / "출발하자마자" — which stop the fill-up comes after, for the sentence. */
    fun afterName(legs: List<Leg>, s: Shortfall): String? = if (s.legIndex == 0) null else legs[s.legIndex - 1].to.place.name

    /** "1%당 km": the car's range divided by its fuel %, when both are known. */
    fun metersPerPercent(fuelPercent: Double?, rangeMeters: Double?): Double? =
        if (fuelPercent != null && fuelPercent > 0 && rangeMeters != null && rangeMeters > 0) rangeMeters / fuelPercent else null
}
