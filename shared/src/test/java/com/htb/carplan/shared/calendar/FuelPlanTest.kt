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

    @Test
    fun `usage says what the day takes and what is left`() {
        // 50% gives 400 km → 8 km per %; 120 km = 15%, 35% left
        val u = FuelPlan.usage(120_000.0, 50.0, 400_000.0)!!
        org.junit.Assert.assertEquals(15.0, u.usedPercent, 0.01)
        org.junit.Assert.assertEquals(35.0, u.leftPercent, 0.01)
        org.junit.Assert.assertNull(FuelPlan.usage(1.0, null, 400_000.0))
    }

    @Test
    fun `search points are few and spread`() {
        org.junit.Assert.assertEquals(listOf(5_000.0, 30_000.0, 55_000.0), FuelPlan.searchPoints(60_000.0))
        // short way: close points collapse into one
        org.junit.Assert.assertEquals(listOf(4_000.0), FuelPlan.searchPoints(4_000.0))
    }

    @Test
    fun `pick prefers cheapest then later`() {
        val p = FuelPlan.pick(listOf(FuelPlan.Candidate(5_000.0, 1_650, "a"), FuelPlan.Candidate(30_000.0, 1_600, "b"), FuelPlan.Candidate(55_000.0, 1_605, "c")))!!
        org.junit.Assert.assertEquals("c", p.best.item) // within 10원 of the cheapest → the later one
        org.junit.Assert.assertEquals(45, p.saving)
        org.junit.Assert.assertNull(FuelPlan.pick<String>(emptyList()))
    }

    @Test
    fun `window starts at half a tank`() {
        // 80% with 1 km per %: 50% comes at 30 km; enough for a 100 km day → 30..100
        org.junit.Assert.assertEquals(30_000.0..100_000.0, FuelPlan.window(80.0, 1_000.0, null, 100_000.0))
        // 80% and only a 20 km day: never below 50% → nothing to suggest
        org.junit.Assert.assertNull(FuelPlan.window(80.0, 1_000.0, null, 20_000.0))
        // 40% already: from now
        org.junit.Assert.assertEquals(0.0..60_000.0, FuelPlan.window(40.0, 1_000.0, 60_000.0, 90_000.0))
        // runs short at 10 km while still at 70%: the last 5 km before that
        org.junit.Assert.assertEquals(5_000.0..10_000.0, FuelPlan.window(80.0, 1_000.0, 10_000.0, 50_000.0))
    }
}
