package com.htb.carplan.shared.calendar

import com.htb.carplan.shared.calendar.RouteMath.hasCoords
import com.htb.carplan.shared.model.LegMemo
import com.htb.carplan.shared.model.LegSample
import com.htb.carplan.shared.model.Place

/**
 * A5 실제 이동 시간 (2026-11-11): what our own car took between two places, so a plan can say "약 25분 · 우리 차 3번"
 * before asking Kakao. A sample is the gap from leaving one record slot to arriving at the next; detours (a stop that
 * left no record, a long wait) are dropped when it is far beyond what the distance allows.
 */
object LegTimes {
    /** Samples kept per pair of places (the newest). */
    const val KEEP = 8
    /** Two places count as the same as a memo's ends within this many metres. */
    const val NEAR_M = 300.0

    /** The memo id for a drive [a] → [b]: both ends rounded to ~100 m. Null without coordinates. */
    fun key(a: Place, b: Place): String? {
        if (!a.hasCoords() || !b.hasCoords()) return null
        fun r(v: Double) = "%.3f".format(java.util.Locale.ROOT, v)
        return "${r(a.lat)},${r(a.lng)}>${r(b.lat)},${r(b.lng)}"
    }

    /**
     * Whether [minutes] is a plain drive over [meters] (straight line): at least a minute, at most 6 hours, and no
     * more than three times the straight-line guess plus 20 minutes (more = a stop or a wait on the way).
     */
    fun plausible(minutes: Int, meters: Double): Boolean =
        minutes in 1..360 && minutes * 60 <= RouteMath.estimateSeconds(meters) * 3 + 20 * 60

    /** [old] with [s] added, the newest [KEEP] kept. */
    fun append(old: List<LegSample>, s: LegSample): List<LegSample> = (old + s).takeLast(KEEP)

    /** The memo for [a] → [b], both ends within [NEAR_M] (closest first). */
    fun find(memos: List<LegMemo>, a: Place, b: Place): LegMemo? {
        if (!a.hasCoords() || !b.hasCoords()) return null
        return memos.filter { it.samples.isNotEmpty() }
            .map { m -> m to RouteMath.metersBetween(m.fromLat, m.fromLng, a.lat, a.lng) + RouteMath.metersBetween(m.toLat, m.toLng, b.lat, b.lng) }
            .filter { (m, _) -> RouteMath.metersBetween(m.fromLat, m.fromLng, a.lat, a.lng) <= NEAR_M && RouteMath.metersBetween(m.toLat, m.toLng, b.lat, b.lng) <= NEAR_M }
            .minByOrNull { it.second }?.first
    }

    /**
     * Expected minutes: the median of the drives that left within two hours of [hour] (traffic differs by time of
     * day), or of all of them when none did / the hour is unknown. Null with no samples.
     */
    fun minutes(memo: LegMemo, hour: Int?): Int? {
        val all = memo.samples.filter { it.minutes > 0 }
        if (all.isEmpty()) return null
        val near = hour?.let { h -> all.filter { s -> minOf((s.hour - h + 24) % 24, (h - s.hour + 24) % 24) <= 2 } }.orEmpty()
        val use = (near.ifEmpty { all }).map { it.minutes }.sorted()
        return if (use.size % 2 == 1) use[use.size / 2] else (use[use.size / 2 - 1] + use[use.size / 2] + 1) / 2
    }
}
