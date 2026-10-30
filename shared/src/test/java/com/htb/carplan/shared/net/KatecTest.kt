package com.htb.carplan.shared.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KatecTest {
    @Test
    fun `seoul city hall lands west of the central meridian and round-trips`() {
        val xy = Katec.fromWgs(37.5665, 126.9780)
        // 128°E is x = 400 000; Seoul is ~1° west → roughly 90 km less.
        assertTrue("x=${xy.x}", xy.x in 290_000.0..330_000.0)
        assertTrue("y=${xy.y}", xy.y in 540_000.0..560_000.0)
        val (lat, lng) = Katec.toWgs(xy.x, xy.y)
        assertEquals(37.5665, lat, 0.0005)
        assertEquals(126.9780, lng, 0.0005)
    }

    @Test
    fun `busan round-trips`() {
        val xy = Katec.fromWgs(35.1796, 129.0756)
        val (lat, lng) = Katec.toWgs(xy.x, xy.y)
        assertEquals(35.1796, lat, 0.0005)
        assertEquals(129.0756, lng, 0.0005)
    }
}
