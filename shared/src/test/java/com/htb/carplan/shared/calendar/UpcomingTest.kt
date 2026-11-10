package com.htb.carplan.shared.calendar

import com.htb.carplan.shared.model.Event
import com.htb.carplan.shared.model.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpcomingTest {
    @Test fun coreDropsTheUnitWord() {
        assertEquals("담양", Upcoming.core("전남 담양군"))
        assertEquals("제주", Upcoming.core("제주시"))
        assertEquals("강릉", Upcoming.core("강릉"))
        assertEquals("중구", Upcoming.core("중구")) // too short to cut
        assertNull(Upcoming.core(" "))
    }

    @Test fun plannedTripCoversItsRegion() {
        val texts = Upcoming.texts(listOf(
            Event(title = "담양 대나무숲·가로수길 하루"),
            Event(title = "출근", place = Place(name = "회사", address = "경기 성남시 분당구")),
            Event(title = "지운 포항", deleted = true),
        ))
        assertTrue(Upcoming.covers("담양군", texts))
        assertTrue(Upcoming.covers("성남시", texts))
        assertFalse(Upcoming.covers("포항시", texts))
        assertFalse(Upcoming.covers("영덕군", texts))
    }
}
