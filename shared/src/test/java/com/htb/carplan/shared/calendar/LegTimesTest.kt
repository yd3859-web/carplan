package com.htb.carplan.shared.calendar

import com.htb.carplan.shared.model.LegMemo
import com.htb.carplan.shared.model.LegSample
import com.htb.carplan.shared.model.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegTimesTest {
    private val pangyo = Place("판교역", 37.3948, 127.1112)
    private val yuldong = Place("율동공원", 37.3802, 127.1497)
    private val memo = LegMemo(fromLat = 37.3948, fromLng = 127.1112, toLat = 37.3802, toLng = 127.1497,
        samples = listOf(LegSample(12, 19), LegSample(14, 20), LegSample(25, 8), LegSample(27, 9), LegSample(23, 8)))

    @Test fun keyRoundsBothEnds() {
        assertEquals("37.395,127.111>37.380,127.150", LegTimes.key(pangyo, yuldong))
        assertNull(LegTimes.key(Place("이름만"), yuldong))
    }

    @Test fun plainDriveIsPlausibleDetourIsNot() {
        val m = RouteMath.meters(pangyo, yuldong)!!
        assertTrue(LegTimes.plausible(12, m))
        assertFalse(LegTimes.plausible(0, m))
        assertFalse(LegTimes.plausible(90, m)) // 3.6 km in 90 min: stopped somewhere
    }

    @Test fun keepsOnlyTheNewest() {
        val many = (1..LegTimes.KEEP).map { LegSample(it, 10) }
        val out = LegTimes.append(many, LegSample(99, 10))
        assertEquals(LegTimes.KEEP, out.size)
        assertEquals(99, out.last().minutes)
        assertEquals(2, out.first().minutes)
    }

    @Test fun findsMemoNearBothEndsOnly() {
        assertNotNull(LegTimes.find(listOf(memo), Place("판교역 2번 출구", 37.3955, 127.1118), yuldong))
        assertNull(LegTimes.find(listOf(memo), yuldong, pangyo)) // the other way is another drive
        assertNull(LegTimes.find(listOf(memo), Place("서현역", 37.3850, 127.1235), yuldong))
    }

    @Test fun medianOfTheSameTimeOfDayElseAll() {
        assertEquals(25, LegTimes.minutes(memo, 8))   // morning: 25, 27, 23
        assertEquals(13, LegTimes.minutes(memo, 19))  // evening: 12, 14
        assertEquals(23, LegTimes.minutes(memo, 14))  // nothing near 14h → all five
        assertEquals(23, LegTimes.minutes(memo, null))
        assertNull(LegTimes.minutes(LegMemo(), 10))
    }
}
