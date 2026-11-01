package com.htb.carplan.phone.calendar

import com.htb.carplan.shared.data.CalendarEvent
import com.htb.carplan.shared.model.Event
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class MonthGridTest {
    private fun ev(id: String, start: String, end: String = start, time: String? = null) =
        CalendarEvent("g", Event(id = id, title = id, startDate = start, endDate = end, startTime = time, allDay = time == null))

    @Test fun gridIsWholeSundayFirstWeeks() {
        // 2026-11: the 1st is a Sunday, the 30th a Monday → Nov 1 .. Dec 5, five rows.
        val r = MonthGrid.of(YearMonth.of(2026, 11))
        assertEquals(LocalDate.of(2026, 11, 1), r.start)
        assertEquals(LocalDate.of(2026, 12, 5), r.endInclusive)
        assertEquals(5, MonthGrid.weeks(YearMonth.of(2026, 11)).size)
        // 2026-10 starts on a Thursday → the grid starts on Sunday 9/27.
        assertEquals(LocalDate.of(2026, 9, 27), MonthGrid.of(YearMonth.of(2026, 10)).start)
    }

    @Test fun barsTakeFirstFreeLaneAndClipToTheWeek() {
        val week = LocalDate.of(2026, 11, 1) // Sunday
        val bars = MonthGrid.bars(week, listOf(
            ev("trip", "2026-10-30", "2026-11-03"), // starts last week → col 0..2
            ev("short", "2026-11-02"),             // overlaps the trip → second lane
            ev("later", "2026-11-05", "2026-11-09"), // free again → lane 0, clipped at Saturday
        )).associateBy { it.item.event.id }
        assertEquals(0, bars.getValue("trip").lane); assertEquals(0, bars.getValue("trip").startCol); assertEquals(2, bars.getValue("trip").endCol)
        assertEquals(1, bars.getValue("short").lane)
        assertEquals(0, bars.getValue("later").lane); assertEquals(4, bars.getValue("later").startCol); assertEquals(6, bars.getValue("later").endCol)
    }

    @Test fun eventsOnPutsAllDayFirstThenByTime() {
        val d = "2026-11-01"
        val list = MonthGrid.eventsOn(LocalDate.parse(d), listOf(ev("b", d, time = "15:00"), ev("a", d, time = "09:00"), ev("allday", d), ev("other", "2026-11-02")))
        assertEquals(listOf("allday", "a", "b"), list.map { it.event.id })
    }
}
