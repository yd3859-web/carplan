package com.htb.carplan.shared.calendar

import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.model.PlaceMemo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceRankTest {
    private fun p(id: String?) = Place(name = id ?: "이름만", kakaoId = id)

    @Test fun againFirstThenVisitedThenKakaoOrder() {
        val list = listOf(p("a"), p("b"), p(null), p("c"), p("d"))
        val memos = mapOf(
            "c" to PlaceMemo("c", againBy = listOf("u1")),
            "d" to PlaceMemo("d", againBy = listOf("u1", "u2")),
            "b" to PlaceMemo("b", visited = true),
        )
        assertEquals(listOf("d", "c", "b", "a", "이름만"), PlaceRank.rank(list, memos).map { it.name })
    }

    @Test fun namedPlaceWithoutKakaoIdMatchesKakaoResultNearby() {
        val typed = Place(name = "판교역", lat = 37.39471, lng = 127.11122)
        val key = PlaceRank.keyOf(typed)!!
        val fromKakao = Place(name = "판교역", lat = 37.39468, lng = 127.11119, kakaoId = "12345")
        assertEquals("또 갈래요 1", PlaceRank.badge(fromKakao, mapOf(key to PlaceMemo(key, againBy = listOf("u1")))))
        assertNull(PlaceRank.keyOf(Place(name = "이름만")))
    }

    @Test fun badges() {
        val memos = mapOf("c" to PlaceMemo("c", againBy = listOf("u1", "u2"), visited = true), "b" to PlaceMemo("b", visited = true))
        assertEquals("또 갈래요 2", PlaceRank.badge(p("c"), memos))
        assertEquals("가 봄", PlaceRank.badge(p("b"), memos))
        assertNull(PlaceRank.badge(p("a"), memos))
        assertNull(PlaceRank.badge(p(null), memos))
    }
}
