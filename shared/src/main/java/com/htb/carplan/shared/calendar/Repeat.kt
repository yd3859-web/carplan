package com.htb.carplan.shared.calendar

import com.htb.carplan.shared.model.Event
import java.time.DayOfWeek
import java.time.LocalDate

/** Repeat rules stored on [Event.repeat]: "DAILY" or "WEEKLY:MO,TU,WE" (16장 출퇴근). */
object Repeat {
    fun weeklyRule(days: Set<DayOfWeek>) = "WEEKLY:" + days.sortedBy { it.value }.joinToString(",") { it.name.take(2) }

    fun days(rule: String?): Set<DayOfWeek> = when {
        rule == null -> emptySet()
        rule == "DAILY" -> DayOfWeek.entries.toSet()
        rule.startsWith("WEEKLY:") -> rule.removePrefix("WEEKLY:").split(",").mapNotNull { code -> DayOfWeek.entries.firstOrNull { it.name.startsWith(code.trim()) } }.toSet()
        else -> emptySet()
    }

    /** True when the event happens on [date]: inside its date range, or on a repeat day on/after its start. */
    fun occursOn(e: Event, date: LocalDate): Boolean {
        if (e.startDate.isEmpty()) return false
        val s = LocalDate.parse(e.startDate)
        val end = LocalDate.parse(e.endDate.ifEmpty { e.startDate })
        if (date in s..end) return true
        if (e.repeat == null || date < s) return false
        return date.dayOfWeek in days(e.repeat)
    }
}
