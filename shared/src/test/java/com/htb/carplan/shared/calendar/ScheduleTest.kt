package com.htb.carplan.shared.calendar

import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.model.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleTest {
    private fun stop(id: String, time: String? = null) = Stop(id = id, place = Place(name = id), time = time)

    @Test
    fun `chains from the last typed time with stay and drive`() {
        val stops = listOf(stop("a", "10:00"), stop("b"), stop("c"))
        val legs = mapOf("a" to 600, "b" to 1800) // 10 min, 30 min
        val est = Schedule.estimate(stops, { legs[it] }, stayMinutes = 60)
        assertEquals("11:10", est["b"]) // 10:00 + 60 + 10
        assertEquals("12:40", est["c"]) // 11:10 + 60 + 30
        assertNull(est["a"])
    }

    @Test
    fun `typed time in the middle resets the chain`() {
        val stops = listOf(stop("a", "09:00"), stop("b", "13:00"), stop("c"))
        val est = Schedule.estimate(stops, { 0 }, stayMinutes = 30)
        assertEquals("13:30", est["c"])
    }

    @Test
    fun `nothing before the first known time`() {
        val est = Schedule.estimate(listOf(stop("a"), stop("b", "12:00"), stop("c")), { null })
        assertNull(est["a"])
        assertEquals("13:00", est["c"])
    }

    @Test
    fun `running late pushes the later typed times`() {
        val stops = listOf(stop("a", "10:00").copy(visited = true), stop("b", "11:00"), stop("c", "12:30"), stop("d"))
        val out = Schedule.asDriven(stops, "b", java.time.LocalTime.of(13, 29))
        assertEquals("13:29", out["b"])
        assertEquals("14:59", out["c"]) // 12:30 + 149 min late
        assertNull(out["d"]) // no typed time, nothing to move
        assertNull(out["a"]) // already visited
    }

    @Test
    fun `early or barely late moves only the next stop`() {
        val stops = listOf(stop("b", "11:00"), stop("c", "12:30"))
        assertEquals(mapOf("b" to "10:40"), Schedule.asDriven(stops, "b", java.time.LocalTime.of(10, 40)))
        assertEquals(mapOf("b" to "11:03"), Schedule.asDriven(stops, "b", java.time.LocalTime.of(11, 3)))
    }
}
