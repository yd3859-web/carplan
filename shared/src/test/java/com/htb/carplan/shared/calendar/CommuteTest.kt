package com.htb.carplan.shared.calendar

import com.google.firebase.Timestamp
import com.htb.carplan.shared.model.Event
import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.model.Record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.Date

class CommuteTest {
    private val office = Place(name = "회사", lat = 37.40, lng = 127.10)
    private val weekdays = Event(title = "출근", startDate = "2026-10-01", endDate = "2026-10-01", allDay = false, startTime = "08:00", place = office, repeat = "WEEKLY:MO,TU,WE,TH,FR")

    @Test fun `repeat occurs on weekdays after the start`() {
        assertTrue(Repeat.occursOn(weekdays, LocalDate.of(2026, 10, 30))) // Friday
        assertTrue(!Repeat.occursOn(weekdays, LocalDate.of(2026, 10, 31))) // Saturday
        assertTrue(!Repeat.occursOn(weekdays, LocalDate.of(2026, 9, 30))) // before start
    }

    @Test fun `candidate within 60 minutes`() {
        assertNotNull(Commute.candidate(listOf(weekdays), LocalDate.of(2026, 10, 30), LocalTime.of(8, 50)))
        assertNull(Commute.candidate(listOf(weekdays), LocalDate.of(2026, 10, 30), LocalTime.of(10, 0)))
        assertNull(Commute.candidate(listOf(weekdays), LocalDate.of(2026, 10, 31), LocalTime.of(8, 0)))
    }

    private fun rec(date: LocalDate, h: Int, m: Int) = date to Record(place = office, arrivedAt = Timestamp(Date(date.year - 1900, date.monthValue - 1, date.dayOfMonth, h, m)))

    @Test fun `three similar mornings suggest a commute`() {
        val recs = listOf(rec(LocalDate.of(2026, 10, 5), 8, 5), rec(LocalDate.of(2026, 10, 12), 8, 20), rec(LocalDate.of(2026, 10, 19), 7, 55))
        val s = Commute.suggest(recs, emptyList())!!
        assertEquals(setOf(DayOfWeek.MONDAY), s.days)
        assertEquals("08:00", s.time)
    }

    @Test fun `already registered place is not suggested`() {
        val recs = listOf(rec(LocalDate.of(2026, 10, 5), 8, 5), rec(LocalDate.of(2026, 10, 12), 8, 20), rec(LocalDate.of(2026, 10, 19), 7, 55))
        assertNull(Commute.suggest(recs, listOf(weekdays)))
    }
}
