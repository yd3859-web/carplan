package com.htb.carplan.shared.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteTest {

    @Test fun kinds() {
        assertEquals("바다", TasteBuild.kindOf("도구해수욕장", "여행 > 관광,명소 > 해수욕장"))
        assertEquals("바다", TasteBuild.kindOf("강구항"))
        assertNull(TasteBuild.kindOf("김포공항"))
        assertEquals("맛집", TasteBuild.kindOf("동해장식당", "음식점 > 한식"))
        assertEquals("맛집", TasteBuild.kindOf("포항수협 송도활어회센터"))
        assertEquals("카페", TasteBuild.kindOf("바다뷰 카페", "음식점 > 카페"))
        assertEquals("자연", TasteBuild.kindOf("율동공원"))
        assertEquals("자연", TasteBuild.kindOf("팔공산"))
        assertEquals("문화", TasteBuild.kindOf("국립경주박물관"))
        assertEquals("캠핑", TasteBuild.kindOf("자라섬 캠핑장"))
        assertNull(TasteBuild.kindOf("이름없는곳"))
    }

    @Test fun regions() {
        assertEquals("포항시", TasteBuild.region("경북 포항시 북구 두호동 1-1"))
        assertEquals("영덕군", TasteBuild.region("경상북도 영덕군 강구면"))
        assertEquals("서울", TasteBuild.region("서울 강남구 역삼동"))
        assertEquals("서울", TasteBuild.region("서울특별시 종로구"))
        assertEquals("제주시", TasteBuild.region("제주특별자치도 제주시 애월읍"))
        assertNull(TasteBuild.region(""))
    }

    @Test fun summary() {
        val pohang = listOf(
            TastePlace("송도해수욕장", "여행 > 관광,명소 > 해수욕장", "경북 포항시 남구", 36.03, 129.37, again = 2),
            TastePlace("물회식당", "음식점 > 한식", "경북 포항시 북구", 36.06, 129.38),
            TastePlace("바다 카페", "음식점 > 카페", "경북 포항시 북구", 36.07, 129.39),
        )
        val gyeongju = listOf(
            TastePlace("국립경주박물관", "", "경북 경주시", 35.83, 129.23),
            TastePlace("황리단길 식당", "음식점 > 한식", "경북 경주시", 35.84, 129.21),
        )
        val t = TasteBuild.from(listOf(TasteTrip(listOf(pohang), members = 3), TasteTrip(listOf(gyeongju), members = 3)))
        assertEquals(2, t.tripCount)
        assertEquals("맛집", t.kinds.first().first) // 2 of 5 sorted places
        assertEquals(listOf("송도해수욕장(포항시)"), t.again)
        assertEquals("포항시" to 3, t.regions.first())
        assertTrue(t.typicalKm != null && t.typicalKm!! >= 1)
        assertEquals("주로 3명이 같이", t.companions)
        val text = TasteBuild.promptText(t)
        assertTrue("또 가고 싶다고 한 곳" in text && "포항시 3번" in text)
    }
}
