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
}
