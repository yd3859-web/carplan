package com.htb.carplan.phone.calendar

import com.htb.carplan.shared.data.CalendarEvent
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Sunday-first weeks covering a month, and multi-day event bars laid out in lanes. */
object MonthGrid {

    /** First Sunday on/before the 1st through last Saturday on/after month end. */
    fun of(month: YearMonth): ClosedRange<LocalDate> {
        val first = month.atDay(1)
        val start = first.minusDays((first.dayOfWeek.value % 7).toLong())
        val last = month.atEndOfMonth()
        val end = last.plusDays((6 - last.dayOfWeek.value % 7).toLong())
        return start..end
    }

    fun weeks(month: YearMonth): List<LocalDate> {
        val range = of(month)
        return generateSequence(range.start) { it.plusWeeks(1) }.takeWhile { it <= range.endInclusive }.toList()
    }

    /** One bar of an event inside a single week row. */
    data class Bar(val item: CalendarEvent, val lane: Int, val startCol: Int, val endCol: Int)

    /** Lays out the week's events: longer and earlier first, each in the first free lane. */
    fun bars(weekStart: LocalDate, events: List<CalendarEvent>): List<Bar> {
        val weekEnd = weekStart.plusDays(6)
        val laneEnds = mutableListOf<Int>()
        return events
            .mapNotNull { ce ->
                val s = LocalDate.parse(ce.event.startDate)
                val e = LocalDate.parse(ce.event.endDate.ifEmpty { ce.event.startDate })
                if (e < weekStart || s > weekEnd) null
                else Triple(ce, col(weekStart, maxOf(s, weekStart)), col(weekStart, minOf(e, weekEnd)))
            }
            .sortedWith(compareBy({ it.second }, { -(it.third - it.second) }, { it.first.event.startTime ?: "" }))
            .map { (ce, sc, ec) ->
                val lane = laneEnds.indexOfFirst { it < sc }.let { free ->
                    if (free >= 0) free.also { laneEnds[it] = ec } else laneEnds.size.also { laneEnds += ec }
                }
                Bar(ce, lane, sc, ec)
            }
    }

    fun eventsOn(date: LocalDate, events: List<CalendarEvent>): List<CalendarEvent> = events.filter {
        val s = LocalDate.parse(it.event.startDate)
        val e = LocalDate.parse(it.event.endDate.ifEmpty { it.event.startDate })
        date in s..e
    }.sortedWith(compareBy({ it.event.allDay.not() }, { it.event.startTime ?: "" }))

    private fun col(weekStart: LocalDate, d: LocalDate) = ChronoUnit.DAYS.between(weekStart, d).toInt()

    fun isSunday(d: LocalDate) = d.dayOfWeek == DayOfWeek.SUNDAY
    fun isSaturday(d: LocalDate) = d.dayOfWeek == DayOfWeek.SATURDAY
}
