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
        MaintenanceItem.TIRE_ROTATION to Interval(10_000, null),
        MaintenanceItem.BRAKE_FLUID to Interval(50_000, 730),
        MaintenanceItem.CABIN_FILTER to Interval(10_000, 180),
        MaintenanceItem.AIR_FILTER to Interval(40_000, null),
        MaintenanceItem.WIPER to Interval(null, 365),
        MaintenanceItem.COOLANT to Interval(200_000, 3650),
        MaintenanceItem.BATTERY_12V to Interval(null, 730),
        MaintenanceItem.WASHER_FLUID to Interval(null, null),
    )

    /**
     * Why each default (2026-11-02 조사): 현대 취급설명서(아이오닉 5 2027 정비표, 현대 상용차 소모품 표)와 일반 정비 안내.
     * 정확한 값은 차종 취급설명서가 우선 — 사용자가 [주기]로 고칠 수 있다.
     */
    val NOTES: Map<MaintenanceItem, String> = mapOf(
        MaintenanceItem.ENGINE_OIL to "취급설명서 보통 조건 1.5만km·1년, 도심 정체·짧은 거리는 가혹 조건이라 1만km로",
        MaintenanceItem.INSPECTION to "1년에 한 번 또는 1.5만km",
        MaintenanceItem.TIRE_PRESSURE to "한 달에 한 번, 차가 낮다고 알리면 바로",
        MaintenanceItem.TIRE_CHANGE to "5만km 또는 5년(홈 깊이 1.6mm가 한계)",
        MaintenanceItem.CAR_WASH to "2주에 한 번",
        MaintenanceItem.TIRE_ROTATION to "1만km마다 앞뒤 바꿔 고르게 닳게(아이오닉 5 정비표)",
        MaintenanceItem.BRAKE_FLUID to "5만km(아이오닉 5 정비표) 또는 2년 — 습기를 먹어 성능이 떨어짐",
        MaintenanceItem.CABIN_FILTER to "6개월 또는 1만km — 미세먼지 많은 계절은 더 자주",
        MaintenanceItem.AIR_FILTER to "4만km(먼지 많은 길은 더 자주), 엔진차만",
        MaintenanceItem.WIPER to "1년 — 줄무늬가 남거나 소리가 나면 바로",
        MaintenanceItem.COOLANT to "처음 20만km 또는 10년, 그 뒤 4만km·2년(장수명 냉각수 기준)",
        MaintenanceItem.BATTERY_12V to "2년마다 점검, 보통 3~5년 사용",
        MaintenanceItem.WASHER_FLUID to "차가 부족하다고 알리면 보충",
    )

    /** What to show for an item the car warns about (CarState.warnings). */
    fun warningText(item: MaintenanceItem): String = when (item) {
        MaintenanceItem.ENGINE_OIL -> "엔진오일이 부족해요"
        MaintenanceItem.BRAKE_FLUID -> "브레이크액이 부족해요"
        MaintenanceItem.WASHER_FLUID -> "워셔액이 부족해요"
        MaintenanceItem.TIRE_PRESSURE -> "타이어 공기압이 낮아요"
        else -> "${item.label} 확인이 필요해요"
    }

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
