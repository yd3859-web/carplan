package com.htb.carplan.shared.calendar

import com.htb.carplan.shared.model.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteMathTest {
    private val seoul = Place("서울역", 37.5547, 126.9707)
    private val daejeon = Place("대전역", 36.3315, 127.4346)
    private val daegu = Place("동대구역", 35.8797, 128.6286)
    private val busan = Place("부산역", 35.1151, 129.0422)
    private val nameOnly = Place("이름만")

    @Test
    fun straightLineDistance() {
        val m = RouteMath.meters(seoul, busan)!!
        assertEquals(325_000.0, m, 10_000.0) // 서울–부산 직선 약 325km
        assertNull(RouteMath.meters(seoul, nameOnly))
    }

    @Test
    fun shortestOrderKeepsFirstAndSortsTheRest() {
        val out = RouteMath.shortestOrder(listOf(seoul, busan, daejeon, daegu)) { a, b -> RouteMath.meters(a, b) }
        assertEquals(listOf(seoul, daejeon, daegu, busan), out)
    }

    @Test
    fun placesWithoutCoordsStayAtTheEnd() {
        val out = RouteMath.shortestOrder(listOf(seoul, nameOnly, busan, daejeon)) { a, b -> RouteMath.meters(a, b) }
        assertEquals(listOf(seoul, daejeon, busan, nameOnly), out)
    }

    @Test
    fun twoItemsUnchanged() {
        assertEquals(listOf(busan, seoul), RouteMath.shortestOrder(listOf(busan, seoul)) { a, b -> RouteMath.meters(a, b) })
    }
}
