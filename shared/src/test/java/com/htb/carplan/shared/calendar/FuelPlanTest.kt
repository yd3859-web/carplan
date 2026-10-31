package com.htb.carplan.shared.calendar

import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.model.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuelPlanTest {
    private fun p(name: String, lat: Double, lng: Double) = Place(name = name, lat = lat, lng = lng)
    private val a = p("집", 37.0, 127.0)
    private val b = Stop(id = "b", place = p("판교역", 37.1, 127.0))
    private val c = Stop(id = "c", place = p("율동공원", 37.2, 127.0))
    private val d = Stop(id = "d", place = p("정자역", 37.3, 127.0))
    private val legs = listOf(FuelPlan.Leg(a, b, 10_000.0), FuelPlan.Leg(b.place, c, 20_000.0), FuelPlan.Leg(c.place, d, 30_000.0))

    @Test
    fun `enough fuel means no plan`() {
        assertNull(FuelPlan.shortfall(legs, reachMeters = 100_000.0, marginMeters = 30_000.0))
    }

    @Test
    fun `runs short in the third leg after 율동공원`() {
        val s = FuelPlan.shortfall(legs, reachMeters = 70_000.0, marginMeters = 30_000.0)!!
        assertEquals(2, s.legIndex)
        assertEquals(40_000.0, s.byMeters, 0.1)
        assertEquals("율동공원", FuelPlan.afterName(legs, s))
    }

    @Test
    fun `runs short right away`() {
        val s = FuelPlan.shortfall(legs, reachMeters = 5_000.0, marginMeters = 30_000.0)!!
        assertEquals(0, s.legIndex)
        assertEquals(0.0, s.byMeters, 0.1)
        assertNull(FuelPlan.afterName(legs, s))
    }

    @Test
    fun `point along the legs`() {
        val pt = FuelPlan.pointAt(legs, 20_000.0)!! // 10 km into leg 2 of 20 km → halfway b→c
        assertEquals(37.15, pt.lat, 1e-6)
    }

    @Test
    fun `metres per percent`() {
        assertEquals(5_000.0, FuelPlan.metersPerPercent(20.0, 100_000.0)!!, 0.1)
        assertNull(FuelPlan.metersPerPercent(0.0, 100_000.0))
    }
}
