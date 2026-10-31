package com.htb.carplan.shared.calendar

import com.htb.carplan.shared.model.Stop
import java.time.LocalTime

/**
 * Estimated arrival times for stops the user left blank (3장 timeline):
 * previous stop's time (typed or estimated) + stay there + drive to this stop.
 * Stops before the first known time get no estimate.
 */
object Schedule {
    const val DEFAULT_STAY_MINUTES = 60

    /**
     * @param legSeconds drive time from the stop with the given id to the next one, or null when unknown.
     * @return stopId → "HH:mm" for stops without a typed time.
     */
    fun estimate(stops: List<Stop>, legSeconds: (fromStopId: String) -> Int?, stayMinutes: Int = DEFAULT_STAY_MINUTES): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var prev: LocalTime? = null
        var prevId: String? = null
        for (s in stops) {
            val typed = s.time?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            val cur = typed ?: prev?.let { p ->
                val leg = prevId?.let(legSeconds) ?: 0
                p.plusMinutes(stayMinutes.toLong()).plusSeconds(leg.toLong())
            }
            if (typed == null && cur != null) out[s.id] = "%02d:%02d".format(cur.hour, cur.minute)
            prev = cur
            prevId = s.id
        }
        return out
    }

    /**
     * The day as it is actually going (정원 2026-10-31: times are expectations, not promises — running late just
     * pushes the rest of the day). [nextId] is the stop the car is driving to and [nextEta] when the navigation
     * expects to get there; it shows that time. When it is [minLateMinutes] or more after the typed time, every
     * later stop with a typed time moves by the same amount. Arriving early moves nothing (more time there).
     * @return stopId → "HH:mm" to show instead of the typed time.
     */
    fun asDriven(stops: List<Stop>, nextId: String?, nextEta: LocalTime?, minLateMinutes: Long = 5): Map<String, String> {
        if (nextId == null || nextEta == null) return emptyMap()
        val idx = stops.indexOfFirst { it.id == nextId }.takeIf { it >= 0 } ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        out[nextId] = fmt(nextEta)
        val planned = stops[idx].time?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return out
        val late = java.time.Duration.between(planned, nextEta).toMinutes()
        if (late < minLateMinutes) return out
        for (s in stops.drop(idx + 1)) {
            if (s.visited) continue
            val t = s.time?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: continue
            out[s.id] = fmt(t.plusMinutes(late))
        }
        return out
    }

    private fun fmt(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)
}
