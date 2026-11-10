package com.htb.carplan.shared.calendar

import org.junit.Assert.assertEquals
import org.junit.Test

class StopMemoTest {
    @Test fun appMemosBecomeTags() {
        assertEquals("투표" to "", StopMemo.split(StopMemo.VOTE))
        assertEquals("다트" to "양천구", StopMemo.split("다트 · 양천구"))
        assertEquals("추천" to "", StopMemo.split("추천"))
        assertEquals("추천" to "", StopMemo.split("AI 추천"))
        assertEquals(null to "여기서 출발", StopMemo.split("여기서 출발"))
    }
}
