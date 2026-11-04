package com.htb.carplan.shared.text

import org.junit.Assert.assertEquals
import org.junit.Test

class JosaTest {
    @Test fun hangul() {
        assertEquals("중2동을", Josa.eul("중2동"))
        assertEquals("율동공원을", Josa.eul("율동공원"))
        assertEquals("카페를", Josa.eul("카페"))
        assertEquals("맛집이", Josa.ga("맛집"))
        assertEquals("카페가", Josa.ga("카페"))
    }

    @Test fun ro() {
        assertEquals("서울로", Josa.ro("서울"))
        assertEquals("판교역으로", Josa.ro("판교역"))
        assertEquals("부산으로", Josa.ro("부산"))
        assertEquals("강릉으로", Josa.ro("강릉"))
        assertEquals("해변으로", Josa.ro("해변"))
    }

    @Test fun digitsAndLatin() {
        assertEquals("3을", Josa.eul("3"))
        assertEquals("2를", Josa.eul("2"))
        assertEquals("Starbucks를", Josa.eul("Starbucks"))
    }
}
