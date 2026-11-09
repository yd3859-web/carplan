package com.htb.carplan.shared.calendar

/**
 * A1 실제 달린 길: thinning the car's positions while driving, and cutting the day's drives into the piece
 * between two record places (the diary map draws that piece instead of a guessed Kakao route).
 * Pure Kotlin so both apps and the unit tests share it.
 */
object TrackMath {
    /** Keep a point only when it is at least this far from the last kept one. */
    const val MIN_STEP_M = 30.0
    /** A record place counts as "on" the drive when the drive passes within this distance (parking lots are off-road). */
    const val NEAR_M = 400.0

    /** Adds [p] to [kept] when it moved far enough; returns whether it was kept. */
    fun keep(kept: MutableList<Pair<Double, Double>>, p: Pair<Double, Double>, minStep: Double = MIN_STEP_M): Boolean {
        val last = kept.lastOrNull()
        if (last != null && RouteMath.metersBetween(last.first, last.second, p.first, p.second) < minStep) return false
        kept += p
        return true
    }

    fun flatten(points: List<Pair<Double, Double>>): List<Double> = points.flatMap { listOf(it.first, it.second) }
    fun unflatten(flat: List<Double>): List<Pair<Double, Double>> = flat.chunked(2).filter { it.size == 2 }.map { it[0] to it[1] }

    /**
     * The driven path from [a] to [b]: in one of [drives] (in time order), the point nearest [a] and, after it,
     * the point nearest [b], both within [NEAR_M]. Null when no single drive went from one to the other
     * (then the caller falls back to a route search). The two places themselves are put at the ends.
     */
    fun legBetween(drives: List<List<Pair<Double, Double>>>, a: Pair<Double, Double>, b: Pair<Double, Double>): List<Pair<Double, Double>>? {
        // Usually one drive per leg (P at a → D → P at b): its start is near a and its end near b.
        // A drive may also pass several places (no stop in between), so search inside each drive too.
        var best: List<Pair<Double, Double>>? = null
        var bestErr = Double.MAX_VALUE
        // Also the joined drives: a short stop (signal, fuel) between a and b splits the drive in two.
        val candidates = drives + listOf(drives.flatten()).filter { drives.size > 1 }
        for (d in candidates) {
            if (d.size < 2) continue
            val ia = nearest(d, a, 0) ?: continue
            val ib = nearest(d, b, ia.first + 1) ?: continue
            val err = ia.second + ib.second
            if (err < bestErr) { bestErr = err; best = listOf(a) + d.subList(ia.first, ib.first + 1) + listOf(b) }
        }
        return best
    }

    /** Index and distance of the point of [d] nearest [p] from [from] on, if within [NEAR_M]. */
    private fun nearest(d: List<Pair<Double, Double>>, p: Pair<Double, Double>, from: Int): Pair<Int, Double>? {
        var bi = -1
        var bm = Double.MAX_VALUE
        for (i in from until d.size) {
            val m = RouteMath.metersBetween(d[i].first, d[i].second, p.first, p.second)
            if (m < bm) { bm = m; bi = i }
        }
        return if (bi >= 0 && bm <= NEAR_M) bi to bm else null
    }
}
