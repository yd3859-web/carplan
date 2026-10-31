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

    /**
     * The day in fuel (정원 2026-10-31: 출발할 때 전체 사용량): [totalMeters] still to drive, as % of the tank,
     * and what is left on arrival. Null when the car does not tell fuel and range.
     */
    fun usage(totalMeters: Double, fuelPercent: Double?, reachMeters: Double?): Usage? {
        val perPercent = metersPerPercent(fuelPercent, reachMeters) ?: return null
        val used = totalMeters / perPercent
        return Usage(totalMeters, used, fuelPercent!! - used)
    }

    data class Usage(val meters: Double, val usedPercent: Double, val leftPercent: Double)

    /**
     * Where along the day to look for prices when the tank runs short: soon after setting off, halfway, and just
     * before it runs short — three Opinet calls, not one per 10 km (API 한도). Points closer than 5 km collapse into the later one.
     */
    fun searchPoints(byMeters: Double): List<Double> = searchPoints(0.0, byMeters)

    /** The same three points, inside [from]..[to] metres along the day. */
    fun searchPoints(from: Double, to: Double): List<Double> =
        listOf(minOf(from + 5_000.0, to), (from + to) / 2, maxOf(from, to - 5_000.0))
            .sorted().fold(emptyList()) { acc, m -> if (acc.isNotEmpty() && m - acc.last() < 5_000.0) acc.dropLast(1) + m else acc + m }

    /** A station found near a point [atMeters] along the day. */
    data class Candidate<T>(val atMeters: Double, val price: Int, val item: T)

    /**
     * The one to suggest: the cheapest; on a tie (within 10원) the later one — less of a detour from the plan,
     * more fuel used first. [saving] = how much cheaper than the first point's best (what "지금 바로" would cost).
     */
    fun <T> pick(found: List<Candidate<T>>): Pick<T>? {
        if (found.isEmpty()) return null
        val low = found.minOf { it.price }
        val best = found.filter { it.price <= low + 10 }.maxByOrNull { it.atMeters }!!
        val firstAt = found.minOf { it.atMeters }
        val soon = found.filter { it.atMeters == firstAt }.minOf { it.price }
        return Pick(best, (soon - best.price).takeIf { it > 0 })
    }

    data class Pick<T>(val best: Candidate<T>, val saving: Int?)

    /** Which stop a point [meters] along the day comes after (null = before the first one). */
    fun afterStopAt(legs: List<Leg>, meters: Double): Stop? {
        var run = 0.0
        var last: Stop? = null
        for (leg in legs) {
            run += leg.meters
            if (run > meters) return last
            last = leg.to
        }
        return last
    }

    /** Nobody fills up at 85 % (정원 2026-10-31): suggestions only once the tank is at or below this. */
    const val SUGGEST_BELOW_PERCENT = 50.0

    /**
     * Where along the day a fill-up makes sense: from where the tank reaches [SUGGEST_BELOW_PERCENT] to where it
     * runs short ([shortAt], or the end of the day [total] when it does not). Null = nothing to suggest today
     * (enough fuel and above 50 % all day). When it runs short before reaching 50 %, the last stretch before that.
     */
    fun window(fuelPercent: Double, metersPerPercent: Double, shortAt: Double?, total: Double): ClosedFloatingPointRange<Double>? {
        val end = shortAt ?: total
        val half = maxOf(0.0, (fuelPercent - SUGGEST_BELOW_PERCENT) * metersPerPercent)
        if (shortAt == null && half >= total) return null
        val start = if (half <= end) half else maxOf(0.0, end - 5_000.0)
        return start..end
    }

    /** "1%당 km": the car's range divided by its fuel %, when both are known. */
    fun metersPerPercent(fuelPercent: Double?, rangeMeters: Double?): Double? =
        if (fuelPercent != null && fuelPercent > 0 && rangeMeters != null && rangeMeters > 0) rangeMeters / fuelPercent else null
}
