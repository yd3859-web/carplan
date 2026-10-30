package com.htb.carplan.shared.data

import com.htb.carplan.shared.calendar.RouteMath
import com.htb.carplan.shared.model.DartScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RegionsTest {
    @Test fun `national pick returns something`() = assertNotNull(Regions.pick(DartScope.NATIONAL, random = Random(1)))
    @Test fun `near pick stays within 100 km of Seoul`() {
        repeat(30) { i ->
            val r = Regions.pick(DartScope.NEAR, 37.5665, 126.9780, random = Random(i))!!
            assertTrue(r.fullName, RouteMath.metersBetween(37.5665, 126.9780, r.lat, r.lng) <= 100_000)
        }
    }
    @Test fun `region pick stays in that 시도`() {
        repeat(10) { i -> assertEquals("제주", Regions.pick(DartScope.REGION, region = "제주", random = Random(i))!!.sido) }
    }
    @Test fun `csv load replaces the list`() {
        val before = Regions.all.size
        try {
            Regions.load(listOf("시도,시군구,읍면동,lat,lng", "서울,종로구,청운효자동,37.584,126.970"))
            assertEquals(1, Regions.all.size)
            assertEquals("서울 종로구 청운효자동", Regions.all.first().fullName)
            Regions.load(emptyList()); assertEquals(1, Regions.all.size) // empty keeps current
            assertTrue(before > 100)
        } finally { Regions.reset() }
    }
}
