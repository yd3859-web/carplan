package com.htb.carplan.shared.calendar

import com.htb.carplan.shared.model.NaviPlace
import com.htb.carplan.shared.model.PlaceMemo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OurPlacesTest {
    private val home = NaviPlace("우리집", "성남시 분당구", 37.38, 127.11, "HOME")
    private val recent = NaviPlace("판교역", "성남시 분당구 판교역로", 37.3948, 127.1112, null)
    private val cafe = PlaceMemo("k1", "율동공원 카페", visited = true, lat = 37.38, lng = 127.15, visits = 3, parking = "B2", stayMinutes = 90)
    private val pangyo = PlaceMemo("n:판교역@37.395,127.111", "판교역", visited = true, lat = 37.3949, lng = 127.1113, visits = 1)

    @Test fun blankQueryListsNaviFirstThenVisited() {
        val items = OurPlaces.list(listOf(recent, home), listOf(cafe))
        assertEquals(listOf("우리집", "판교역", "율동공원 카페"), items.map { it.place.name })
        assertEquals("우리 차 · 집", items[0].label)
        assertTrue(items[0].navi && !items[2].navi)
    }

    @Test fun samePlaceShowsOnceWithWhatTheCarLearned() {
        val items = OurPlaces.list(listOf(recent), listOf(pangyo))
        assertEquals(1, items.size)
        assertEquals("우리 차 · 최근 · 가 봄", items[0].label)
    }

    @Test fun queryMatchesNameOrAddressIgnoringSpaces() {
        assertEquals(listOf("율동공원 카페"), OurPlaces.list(listOf(home, recent), listOf(cafe), "율동 공원").map { it.place.name })
        assertEquals(listOf("우리집", "판교역"), OurPlaces.list(listOf(home, recent), listOf(cafe), "분당구").map { it.place.name })
        assertTrue(OurPlaces.list(listOf(home), listOf(cafe), "부산").isEmpty())
    }

    @Test fun memoLineSaysVisitsParkingAndStay() {
        assertEquals("가 봄 3번 · 주차 B2 · 1시간 30분 머묾", OurPlaces.memoLine(cafe))
        assertEquals("또 갈래요 2 · 가 봄", OurPlaces.memoLine(PlaceMemo("x", "a", againBy = listOf("u1", "u2"), visited = true, stayMinutes = 0)))
    }

    @Test fun unnamedHomeGoesByItsKindAndEmptySlotsAreLeftOut() {
        val items = OurPlaces.list(listOf(NaviPlace("", "성남시 수정구", 37.44, 127.13, "HOME"), NaviPlace("", "", 0.0, 0.0, "OFFICE")), emptyList())
        assertEquals(listOf("집"), items.map { it.place.name })
        assertEquals(listOf("집"), OurPlaces.list(listOf(NaviPlace("", "성남시 수정구", 37.44, 127.13, "HOME")), emptyList(), "집").map { it.place.name })
    }

    @Test fun notVisitedMemosAreLeftOut() {
        assertTrue(OurPlaces.list(emptyList(), listOf(PlaceMemo("k", "어딘가", againBy = listOf("u")))).isEmpty())
    }
}
