package com.htb.carplan.shared.calendar

import com.htb.carplan.shared.model.MaintenanceItem
import java.time.LocalDate

/** 15장 Q41·Q42: when each item is due next — the earlier of the km rule and the date rule. */
object MaintenanceRules {
    /** km / days; null = that rule does not apply. INSURANCE has neither (user types the renewal date). */
    data class Interval(val km: Long?, val days: Long?)

    val DEFAULTS: Map<MaintenanceItem, Interval> = mapOf(
        MaintenanceItem.ENGINE_OIL to Interval(10_000, 365),
        MaintenanceItem.INSPECTION to Interval(15_000, 365),
        MaintenanceItem.TIRE_PRESSURE to Interval(null, 30),
        MaintenanceItem.TIRE_CHANGE to Interval(50_000, null),
        MaintenanceItem.CAR_WASH to Interval(null, 14),
        MaintenanceItem.INSURANCE to Interval(null, null),
        MaintenanceItem.FUEL to Interval(null, null),
    )

    /** Fallback daily distance when the car's real average is unknown. */
    const val DEFAULT_KM_PER_DAY = 40.0

    data class Due(val date: LocalDate?, val dueKm: Double?, val byKm: Boolean)

    fun interval(item: MaintenanceItem, overrides: Map<String, Map<String, Long>>): Interval {
        val o = overrides[item.name] ?: return DEFAULTS.getValue(item)
        return Interval(o["km"]?.takeIf { it > 0 }, o["days"]?.takeIf { it > 0 })
    }

    /**
     * Next due after a record on [lastDate] at [lastKm]. The km rule becomes a date through
     * [kmPerDay] so the two can be compared; the earlier wins.
     */
    fun nextDue(item: MaintenanceItem, lastDate: LocalDate, lastKm: Double?, interval: Interval, kmPerDay: Double = DEFAULT_KM_PER_DAY): Due? {
        val byDate = interval.days?.let { lastDate.plusDays(it) }
        val dueKm = if (interval.km != null && lastKm != null) lastKm + interval.km else null
        val byKmDate = dueKm?.let { lastDate.plusDays((interval.km!! / kmPerDay).toLong().coerceAtLeast(1)) }
        return when {
            byDate != null && byKmDate != null -> if (byKmDate < byDate) Due(byKmDate, dueKm, true) else Due(byDate, dueKm, false)
            byDate != null -> Due(byDate, null, false)
            byKmDate != null -> Due(byKmDate, dueKm, true)
            else -> null
        }
    }
}
