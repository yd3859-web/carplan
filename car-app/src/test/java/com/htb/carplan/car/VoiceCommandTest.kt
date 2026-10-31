package com.htb.carplan.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandTest {
    @Test
    fun `everyday phrases`() {
        assertEquals(VoiceCommand.FUEL, VoiceCommand.parse("주유소 찾아줘"))
        assertEquals(VoiceCommand.FUEL, VoiceCommand.parse("근처 충전소"))
        assertEquals(VoiceCommand.PARKING, VoiceCommand.parse("주차장 어디 있어"))
        assertEquals(VoiceCommand.WHERE, VoiceCommand.parse("다음 어디야"))
        assertEquals(VoiceCommand.WHERE, VoiceCommand.parse("얼마나 남았어"))
        assertEquals(VoiceCommand.SKIP, VoiceCommand.parse("여기 건너뛰자"))
        assertEquals(VoiceCommand.REVERT, VoiceCommand.parse("원래대로 해 줘"))
        assertEquals(VoiceCommand.START, VoiceCommand.parse("안내 시작"))
    }

    @Test
    fun `order decides overlaps`() {
        // "주차장 어디" is a parking request, not a where-question; "원래대로 가자" is a revert.
        assertEquals(VoiceCommand.PARKING, VoiceCommand.parse("주차장 어디"))
        assertEquals(VoiceCommand.REVERT, VoiceCommand.parse("원래대로 가자"))
    }

    @Test
    fun `nothing matches`() {
        assertNull(VoiceCommand.parse("음악 틀어 줘"))
        assertNull(VoiceCommand.parse(""))
        assertNull(VoiceCommand.parse(null))
    }
}
