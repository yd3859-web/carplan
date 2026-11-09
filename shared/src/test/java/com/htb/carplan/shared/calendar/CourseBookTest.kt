package com.htb.carplan.shared.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseBookTest {

    private val text = """
        # 주석 줄
        담양군|자연,맛집|죽녹원;관방제림;담양국수거리|대나무숲
        강릉시|바다,카페|안목해변 커피거리;강릉중앙시장;경포해변|
        포항시|바다,맛집|호미곶;죽도시장
        망가진줄|하나뿐
    """.trimIndent()

    @Test fun parsing() {
        val cs = CourseBook.parse(text)
        assertEquals(listOf("담양군", "강릉시", "포항시"), cs.map { it.region })
        assertEquals(listOf("죽녹원", "관방제림", "담양국수거리"), cs[0].places)
        assertEquals("대나무숲", cs[0].note)
        assertEquals("담양", cs[0].word)
        assertEquals("강릉", cs[1].word)
    }

    @Test fun candidatePick() {
        val cs = CourseBook.parse(text)
        val taste = Taste(3, kinds = listOf("바다" to 60, "맛집" to 40), again = emptyList(),
            regions = listOf("포항시" to 3), typicalKm = 40, companions = "주로 2명이 같이")
        val picked = CourseBook.candidates(cs, taste, avoid = emptyList(), n = 2)
        // 포항시는 가 봐서 빠지고, 바다가 1순위라 강릉이 담양보다 앞.
        assertEquals(listOf("강릉시", "담양군"), picked.map { it.region })
        val other = CourseBook.candidates(cs, taste, avoid = listOf("강릉시"), n = 2)
        assertEquals(listOf("담양군"), other.map { it.region })
        assertTrue("강릉" in CourseBook.promptLines(picked))
    }
}
