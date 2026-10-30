package com.htb.carplan.shared.calendar

import com.htb.carplan.shared.model.Event
import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.model.Record
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs

/** 16장 출퇴근: which repeating place the car should offer now, and what the records suggest to register. */
object Commute {
    const val WINDOW_MINUTES = 60L
    const val MIN_REPEATS = 3
    const val SAME_PLACE_METERS = 300.0

    /** A repeating event with a place that occurs today and starts within ±[WINDOW_MINUTES] of [now]. */
    fun candidate(events: List<Event>, date: LocalDate, now: LocalTime): Event? =
        events.filter { it.repeat != null && it.place != null && !it.deleted && Repeat.occursOn(it, date) }
            .mapNotNull { e -> e.startTime?.let { t -> runCatching { LocalTime.parse(t) }.getOrNull()?.let { e to it } } }
            .filter { (_, t) -> abs(java.time.Duration.between(t, now).toMinutes()) <= WINDOW_MINUTES }
            .minByOrNull { (_, t) -> abs(java.time.Duration.between(t, now).toMinutes()) }?.first

    data class Suggestion(val place: Place, val days: Set<DayOfWeek>, val time: String, val count: Int)

    /**
     * Q45: records (date + record) that repeat on the same weekday at a similar hour at the same place,
     * at least [MIN_REPEATS] times, and not already covered by a repeating event with that place.
     */
    fun suggest(records: List<Pair<LocalDate, Record>>, existing: List<Event>): Suggestion? {
        val withCoords = records.filter { (_, r) -> r.place.lat != 0.0 || r.place.lng != 0.0 }.mapNotNull { (d, r) ->
            val t = r.arrivedAt?.toDate() ?: return@mapNotNull null
            val lt = LocalTime.of(t.hours, t.minutes)
            Triple(d, r, lt)
        }
        // cluster by place (300 m) then by weekday
        val clusters = mutableListOf<MutableList<Triple<LocalDate, Record, LocalTime>>>()
        for (x in withCoords) {
            val c = clusters.firstOrNull { RouteMath.metersBetween(it[0].second.place.lat, it[0].second.place.lng, x.second.place.lat, x.second.place.lng) <= SAME_PLACE_METERS }
            if (c != null) c += x else clusters += mutableListOf(x)
        }
        for (c in clusters) {
            val byDay = c.groupBy { it.first.dayOfWeek }
            val goodDays = byDay.filter { (_, list) ->
                // same hour band: all within 60 min of the median
                val minutes = list.map { it.third.toSecondOfDay() / 60 }.sorted()
                val med = minutes[minutes.size / 2]
                list.count { abs(it.third.toSecondOfDay() / 60 - med) <= WINDOW_MINUTES } >= MIN_REPEATS
            }.keys
            if (goodDays.isEmpty()) continue
            val place = c[0].second.place
            val already = existing.any { e -> e.repeat != null && e.place != null && RouteMath.metersBetween(e.place.lat, e.place.lng, place.lat, place.lng) <= SAME_PLACE_METERS }
            if (already) continue
            val minutes = c.filter { it.first.dayOfWeek in goodDays }.map { it.third.toSecondOfDay() / 60 }.sorted()
            val med = minutes[minutes.size / 2]
            return Suggestion(place, goodDays, "%02d:%02d".format(med / 60, (med % 60) / 10 * 10), c.count { it.first.dayOfWeek in goodDays })
        }
        return null
    }
}
