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
}
