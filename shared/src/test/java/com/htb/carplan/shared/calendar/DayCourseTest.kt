package com.htb.carplan.shared.calendar

import com.htb.carplan.shared.model.Place
import org.junit.Assert.assertEquals
import org.junit.Test

class DayCourseTest {
    private fun p(n: String, lat: Double) = Place(name = n, lat = lat, lng = 127.0, kakaoId = n)

    @Test fun sightLunchSightCafeEachNearestToTheLast() {
        val c = DayCourse.build(37.00, 127.0,
            sights = listOf(p("먼 명소", 37.10), p("가까운 명소", 37.01), p("중간 명소", 37.05)),
            foods = listOf(p("식당A", 37.02), p("식당B", 37.20)),
            cafes = listOf(p("카페A", 37.06), p("카페B", 36.90)))
        assertEquals(listOf("가까운 명소", "식당A", "중간 명소", "카페A"), c.map { it.name })
    }

    @Test fun missingKindsAreSkippedNotFaked() {
        val c = DayCourse.build(37.0, 127.0, sights = listOf(p("명소", 37.01)), foods = emptyList(), cafes = emptyList())
        assertEquals(listOf("명소"), c.map { it.name })
    }

    @Test fun nothingFoundIsEmpty() {
        assertEquals(emptyList<Place>(), DayCourse.build(37.0, 127.0, emptyList(), emptyList(), emptyList()))
    }
}
