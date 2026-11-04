package com.htb.carplan.car

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.htb.carplan.shared.calendar.Repeat
import com.htb.carplan.shared.data.CalendarRepository
import com.htb.carplan.shared.data.Codes
import com.htb.carplan.shared.data.FirestorePaths
import com.htb.carplan.shared.data.GroupRepository
import com.htb.carplan.shared.data.MaintenanceRepository
import com.htb.carplan.shared.data.RecordRepository
import com.htb.carplan.shared.data.TripRepository
import com.htb.carplan.shared.model.BuiltInCategory
import com.htb.carplan.shared.model.DayStatus
import com.htb.carplan.shared.model.Event
import com.htb.carplan.shared.model.Friend
import com.htb.carplan.shared.model.MaintenanceItem
import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.model.Record
import com.htb.carplan.shared.model.User
import kotlinx.coroutines.tasks.await
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date

/**
 * 시연 메뉴 [오래 쓴 것처럼 채우기] (정원 2026-10-31): two months of made-up use, so every screen has something real
 * to show — friends with names, groups, past trips with records (times, notes, photos, comments), fuel and service
 * history, a weekday commute and plans ahead.
 *
 * Friends are real anonymous accounts made in separate FirebaseApp instances (each may only write its own profile,
 * 18장); everything else is written by the car, which acts with its owner's rights. Photos are macOS sample landscapes
 * in the debug assets (none in release: records then go without photos). Everything made is remembered so
 * [clear] can take it away again.
 */
class DemoSeed(private val context: Context, private val ownerUid: String, private val soloGroupId: String, private val carId: String, private val say: (String) -> Unit) {
    private val calendar = CalendarRepository()
    private val groups = GroupRepository()
    private val trips = TripRepository()
    private val records = RecordRepository()
    private val care = MaintenanceRepository()
    private val prefs = context.getSharedPreferences("demo_seed", Context.MODE_PRIVATE)
    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.now(zone)
    private val photos: List<ByteArray> by lazy {
        runCatching { context.assets.list("demo").orEmpty().sorted().map { n -> context.assets.open("demo/$n").use { it.readBytes() } } }.getOrDefault(emptyList())
    }
    private var photoTurn = 0
    private val made = mutableListOf<String>() // "gid/eid" of every event made

    val seeded: Boolean get() = prefs.getBoolean("done", false)

    private data class P(val name: String, val lat: Double, val lng: Double, val address: String = "") {
        val place get() = Place(name = name, lat = lat, lng = lng, address = address)
    }

    /** A planned place of a past day and what happened there. */
    private data class Visit(val p: P, val plan: String?, val arrive: String, val stayMin: Int, val memo: String = "", val note: String = "", val photos: Int = 0, val comments: List<Pair<Int, String>> = emptyList(), val parking: String = "")

    suspend fun run() {
        if (seeded) { fillPhotos(); return }
        say("친구 만드는 중")
        val friends = listOf("민지", "준호", "서연", "엄마").mapIndexed { i, n -> makeFriend(i, n) }
        val (minji, junho, seoyeon, mom) = friends
        say("그룹 만드는 중")
        val weekend = group("주말 친구들", listOf(minji, seoyeon))
        val carpool = group("출근 카풀", listOf(junho))
        val family = group("가족", listOf(mom))

        say("지난 여행 넣는 중")
        trip(weekend, "강릉 바다 여행", today.minusDays(20), listOf(
            listOf(
                Visit(P("안목해변 커피거리", 37.7716, 128.9475, "강원 강릉시 창해로14번길"), "11:00", "11:20", 70, "커피", "바다 보면서 라떼. 바람이 엄청 셌다", 2, listOf(0 to "여기 2층 창가 자리 최고", 1 to "다음엔 일찍 와서 자리 잡자")),
                Visit(P("초당순두부마을", 37.7905, 128.9150, "강원 강릉시 초당순두부길"), "13:00", "13:05", 60, "점심", "짬뽕순두부 줄 30분. 그래도 맛있음", 1, listOf(1 to "주차는 마을 공영주차장이 편해요"), "공영주차장 B구역"),
                Visit(P("경포해변", 37.8055, 128.9087, "강원 강릉시 창해로 514"), "15:00", "14:50", 90, "산책", "해 질 때까지 걸었다", 2, listOf(0 to "노을 사진 단톡에 올렸어")),
            ),
            listOf(
                Visit(P("주문진항", 37.8932, 128.8310, "강원 강릉시 주문진읍"), "10:00", "10:15", 80, "아침 시장", "오징어순대랑 대게 구경", 1, listOf(0 to "건어물은 마지막에 사자")),
                Visit(P("정동진역", 37.6912, 129.0346, "강원 강릉시 강동면 정동진리"), "13:30", "13:40", 50, "", "기차 지나가는 거 보고 출발", 1),
            ),
        ))
        trip(weekend, "가평 캠핑", today.minusDays(41), listOf(listOf(
            Visit(P("가평 잣향기푸른숲", 37.7920, 127.4170, "경기 가평군 상면"), "10:30", "10:50", 100, "숲길", "숲 냄새 좋았다. 데크길 추천", 2),
            Visit(P("남이섬 선착장", 37.7898, 127.5250, "경기 가평군 가평읍 북한강변로"), "13:30", "13:40", 150, "배 타기", "자전거 2인용 빌림", 1, listOf(1 to "배 시간표 30분 간격이에요")),
            Visit(P("자라섬 캠핑장", 37.8167, 127.5240, "경기 가평군 가평읍 자라섬로"), "17:00", "17:20", 0, "캠핑", "고기 굽고 별 봄", 2, listOf(0 to "장작 두 묶음이면 충분")),
        )))
        trip(family, "추석 부모님 댁", today.minusDays(27), listOf(listOf(
            Visit(P("수원화성 행궁", 37.2819, 127.0141, "경기 수원시 팔달구 정조로 825"), "11:00", "11:10", 80, "", "엄마랑 행궁 한 바퀴", 1, listOf(3 to "다음엔 야간개장 가자")),
            Visit(P("수원 통닭거리", 37.2794, 127.0171, "경기 수원시 팔달구 정조로800번길"), "13:00", "12:55", 60, "점심", "반반 두 마리. 포장도 함", 1, parking = "팔달문 공영주차장"),
        )))
        trip(null, "서울 야경 드라이브", today.minusDays(13), listOf(listOf(
            Visit(P("남산서울타워", 37.5512, 126.9882, "서울 용산구 남산공원길 105"), "19:30", "19:45", 60, "야경", "케이블카 대신 버스 탔다", 1),
            Visit(P("반포한강공원", 37.5106, 126.9958, "서울 서초구 신반포로11길 40"), "21:00", "21:05", 40, "분수", "달빛무지개분수 마지막 회차 봤다", 1),
        )))
        trip(family, "양평 두물머리", today.minusDays(6), listOf(listOf(
            Visit(P("두물머리", 37.5345, 127.3170, "경기 양평군 양서면 양수리"), "10:00", "10:20", 60, "산책", "물안개는 못 봤지만 조용해서 좋았다", 2, listOf(3 to "연핫도그 맛있더라")),
            Visit(P("세미원", 37.5384, 127.3112, "경기 양평군 양서면 양수로 93"), "11:30", "11:35", 70, "", "연꽃은 졌지만 배다리 건넘", 1),
        )))

        say("일상 기록 넣는 중")
        daily(today.minusDays(9), P("스타벅스 정자역점", 37.3668, 127.1080, "경기 성남시 분당구 정자일로"), "08:10", 25, "출근 전 커피", 1)
        daily(today.minusDays(2), P("이마트 분당점", 37.3510, 127.1100, "경기 성남시 분당구 황새울로"), "18:40", 50, "주말 장보기. 지하 2층 주차", 1, "B2 C-14")
        daily(today.minusDays(2), P("동네 셀프세차장", 37.3602, 127.1215, "경기 성남시 분당구"), "20:00", 30, "세차 완료. 물왁스까지", 1)

        say("차량 관리 기록 넣는 중")
        listOf(58 to 64000L, 47 to 71000L, 36 to 59000L, 25 to 68000L, 14 to 66000L, 4 to 70000L).forEachIndexed { i, (ago, won) ->
            made += "$soloGroupId/" + care.log(soloGroupId, carId, MaintenanceItem.FUEL, today.minusDays(ago.toLong()), 41_200.0 + i * 430, amountWon = won, liters = won / 1690.0, byUid = ownerUid)
        }
        made += "$soloGroupId/" + care.log(soloGroupId, carId, MaintenanceItem.ENGINE_OIL, today.minusDays(150), 37_900.0, amountWon = 89000, memo = "블루핸즈", byUid = ownerUid)
        made += "$soloGroupId/" + care.log(soloGroupId, carId, MaintenanceItem.TIRE_PRESSURE, today.minusDays(33), 42_100.0, byUid = ownerUid)
        made += "$soloGroupId/" + care.log(soloGroupId, carId, MaintenanceItem.CAR_WASH, today.minusDays(2), 43_700.0, amountWon = 6000, byUid = ownerUid)
        made += "$soloGroupId/" + care.log(soloGroupId, carId, MaintenanceItem.INSPECTION, today.minusDays(240), 33_500.0, memo = "정기 점검", byUid = ownerUid)
        care.setDueDate(soloGroupId, carId, MaintenanceItem.INSURANCE, today.plusDays(47), ownerUid)
        // The next-due events the records above wrote (due-*) go with them on [clear].
        listOf(MaintenanceItem.FUEL, MaintenanceItem.ENGINE_OIL, MaintenanceItem.TIRE_PRESSURE, MaintenanceItem.CAR_WASH, MaintenanceItem.INSPECTION, MaintenanceItem.INSURANCE)
            .forEach { made += "$soloGroupId/" + FirestorePaths.dueEventId(it.name) }

        say("앞으로의 일정 넣는 중")
        val commute = event(carpool, Event(title = "출근", startDate = today.minusDays(40).toString(), endDate = today.minusDays(40).toString(), allDay = false, startTime = "08:20",
            place = P("판교역", 37.3948, 127.1112, "경기 성남시 분당구 판교역로").place, repeat = Repeat.weeklyRule(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))))
        made += commute
        val autumn = today.plusDays(7)
        tripPlan(weekend, "설악산 단풍 여행", autumn, listOf(
            Triple(P("설악산 소공원", 38.1720, 128.4880, "강원 속초시 설악산로 833"), "10:00", "케이블카"),
            Triple(P("속초 중앙시장", 38.2048, 128.5900, "강원 속초시 중앙로147번길"), "13:00", "닭강정"),
            Triple(P("대포항", 38.1745, 128.6070, "강원 속초시 대포항길"), "16:00", "회"),
        ), listOf(0 to "케이블카 예약했어 10시 반", 1 to "닭강정은 내가 살게"))
        made += event(null, Event(title = "치과", startDate = today.plusDays(3).toString(), endDate = today.plusDays(3).toString(), allDay = false, startTime = "15:00", place = P("서현 미소치과", 37.3850, 127.1235).place))
        made += event(null, Event(title = "팀 회식", startDate = today.plusDays(5).toString(), endDate = today.plusDays(5).toString(), allDay = false, startTime = "19:00", place = P("판교 고깃집", 37.3955, 127.1090).place))
        made += event(null, Event(title = "민지 생일", startDate = today.plusDays(11).toString(), endDate = today.plusDays(11).toString()))
        made += event(null, Event(categoryId = BuiltInCategory.CAR.id, title = "엔진오일 교체 예약", startDate = today.plusDays(14).toString(), endDate = today.plusDays(14).toString(), allDay = false, startTime = "10:00", place = P("블루핸즈 분당점", 37.3712, 127.1180).place))
        made += event(family, Event(title = "엄마 생신 저녁", startDate = today.plusDays(28).toString(), endDate = today.plusDays(28).toString(), allDay = false, startTime = "18:30", place = P("수원 갈비집", 37.2760, 127.0290).place))

        prefs.edit().putBoolean("done", true).putStringSet("events", made.toSet()).putStringSet("groups", setOf(weekend, carpool, family)).apply()
        say("다 채웠어요 · 친구 4명, 그룹 3개, 지난 여행 5개, 앞으로 일정 7개")
    }

    /**
     * 시연 메뉴 [여행 기록 더 채우기] (정원 2026-11-04: "여행 기록 같은 거 많이, 이것저것"): ten more past trips around the
     * country (one to three days, notes, comments by the made friends, parking, 또 갈래요) and a dozen 일상 기록, in
     * the groups [run] made. Each press adds a fresh set (remembered for [clear]). Photos go to Storage; when Storage
     * refuses (rules not deployed), a web sample picture stands in so the diary still shows photos.
     */
    suspend fun runMore() {
        if (!seeded) { say("먼저 [오래 쓴 것처럼 채우기]를 눌러 주세요"); return }
        val db = FirebaseFirestore.getInstance()
        val mine = runCatching { db.collection(FirestorePaths.GROUPS).whereArrayContains("memberIds", ownerUid).get().await().documents }.getOrDefault(emptyList())
        fun byName(n: String) = mine.firstOrNull { it.getString("name") == n }?.id
        val weekend = byName("주말 친구들"); val family = byName("가족"); val carpool = byName("출근 카풀")
        sample = 0
        say("여행 기록 더 넣는 중")
        trip(weekend, "부산 2박 3일", today.minusDays(55), listOf(
            listOf(
                Visit(P("해운대해수욕장", 35.1587, 129.1604, "부산 해운대구 우동"), "13:00", "13:20", 90, "바다", "도착하자마자 발 담금. 물이 생각보다 따뜻", 2, listOf(0 to "파라솔 빌리는 데 2만원", 1 to "모래 진짜 곱다")),
                Visit(P("더베이101", 35.1565, 129.1517, "부산 해운대구 동백로 52"), "18:30", "18:40", 80, "야경", "마린시티 야경 보면서 피시앤칩스", 2, listOf(1 to "여기 사진 맛집이야")),
            ),
            listOf(
                Visit(P("감천문화마을", 35.0975, 129.0106, "부산 사하구 감내2로 203"), "10:00", "10:15", 110, "골목", "어린왕자 동상 줄 20분. 계단 많음", 2, listOf(0 to "편한 신발 필수")),
                Visit(P("자갈치시장", 35.0967, 129.0305, "부산 중구 자갈치해안로 52"), "13:00", "13:10", 70, "점심", "2층에서 회 떠서 먹음", 1, parking = "자갈치 공영주차장"),
                Visit(P("광안리해수욕장", 35.1532, 129.1186, "부산 수영구 광안해변로 219"), "20:00", "20:10", 90, "드론쇼", "드론쇼 보고 맥주 한 잔", 2, listOf(0 to "토요일 8시 드론쇼!")),
            ),
            listOf(
                Visit(P("해동용궁사", 35.1883, 129.2233, "부산 기장군 기장읍 용궁길 86"), "09:30", "09:40", 60, "", "바다 옆 절. 아침이라 한산", 1),
                Visit(P("기장시장", 35.2443, 129.2187, "부산 기장군 기장읍 읍내로 104"), "11:30", "11:35", 50, "대게", "대게 사서 집으로", 1),
            ),
        ))
        trip(weekend, "전주 한옥마을", today.minusDays(68), listOf(listOf(
            Visit(P("전주한옥마을", 35.8150, 127.1530, "전북 전주시 완산구 기린대로 99"), "11:00", "11:15", 120, "한복", "한복 빌려 입고 골목 산책", 2, listOf(1 to "한복 대여 2시간 만오천원")),
            Visit(P("전동성당", 35.8133, 127.1494, "전북 전주시 완산구 태조로 51"), "13:30", "13:35", 30, "", "성당 앞에서 단체 사진", 1),
            Visit(P("남부시장 야시장", 35.8117, 127.1460, "전북 전주시 완산구 풍남문1길"), "19:00", "19:10", 80, "야시장", "가맥이랑 꼬치. 사람 엄청 많음", 1, listOf(0 to "금토만 열어요"), "남부시장 주차타워 3층"),
        )))
        trip(family, "경주 가을 여행", today.minusDays(76), listOf(
            listOf(
                Visit(P("불국사", 35.7900, 129.3320, "경북 경주시 불국로 385"), "10:00", "10:20", 100, "", "다보탑 석가탑. 엄마가 제일 좋아하심", 2, listOf(0 to "단풍 조금 들었더라")),
                Visit(P("황리단길", 35.8380, 129.2100, "경북 경주시 포석로"), "13:00", "13:15", 90, "점심", "십원빵 하나씩", 1, parking = "황남 공영주차장"),
                Visit(P("동궁과 월지", 35.8347, 129.2266, "경북 경주시 원화로 102"), "19:00", "19:05", 60, "야경", "연못에 비친 누각. 올해 본 야경 중 최고", 2, listOf(0 to "삼각대 가져올걸")),
            ),
            listOf(
                Visit(P("대릉원", 35.8384, 129.2117, "경북 경주시 황남동 31-1"), "10:00", "10:10", 60, "산책", "목련 나무 포토존", 1),
                Visit(P("경주 보문호", 35.8430, 129.2860, "경북 경주시 보문로"), "12:00", "12:10", 70, "", "오리배 타고 마무리", 1),
            ),
        ))
        trip(null, "여수 밤바다", today.minusDays(90), listOf(
            listOf(
                Visit(P("여수 해상케이블카", 34.7484, 127.7493, "전남 여수시 돌산읍 돌산로 3600-1"), "17:00", "17:10", 60, "케이블카", "크리스탈 캐빈 바닥이 투명해서 무서움", 1),
                Visit(P("여수 낭만포차거리", 34.7385, 127.7342, "전남 여수시 하멜로"), "20:00", "20:05", 100, "포차", "삼합에 소주 한 잔. 노래 그대로", 2),
            ),
            listOf(
                Visit(P("향일암", 34.5913, 127.8030, "전남 여수시 돌산읍 향일암로 60"), "06:30", "06:40", 70, "일출", "새벽에 올라가서 해 뜨는 거 봄", 2),
                Visit(P("이순신광장", 34.7397, 127.7370, "전남 여수시 중앙동"), "11:00", "11:10", 50, "", "갓김치 사 옴", 1),
            ),
        ))
        trip(weekend, "춘천 닭갈비", today.minusDays(34), listOf(listOf(
            Visit(P("명동 닭갈비골목", 37.8805, 127.7270, "강원 춘천시 금강로62번길"), "12:30", "12:40", 60, "점심", "볶음밥까지 국룰", 1, listOf(1 to "2층 자리 넓어요")),
            Visit(P("소양강 스카이워크", 37.8930, 127.7240, "강원 춘천시 영서로 2663"), "14:30", "14:35", 40, "", "유리 바닥 아래 강물", 1),
            Visit(P("김유정역 레일바이크", 37.8180, 127.7180, "강원 춘천시 신동면 김유정로 1383"), "16:00", "16:10", 80, "레일바이크", "다리 터질 뻔. 터널 조명 예쁨", 2, listOf(0 to "예약 필수!")),
        )))
        trip(family, "대전 성심당 빵 투어", today.minusDays(48), listOf(listOf(
            Visit(P("성심당 본점", 36.3277, 127.4273, "대전 중구 대종로480번길 15"), "10:30", "10:40", 50, "빵", "튀김소보로 한 박스. 줄 40분", 2, listOf(3 to "망고시루는 품절이더라")),
            Visit(P("대전 한밭수목원", 36.3670, 127.3880, "대전 서구 둔산대로 169"), "13:00", "13:10", 70, "산책", "빵 먹으면서 수목원", 1),
        )))
        trip(carpool, "퇴근길 인천 차이나타운", today.minusDays(17), listOf(listOf(
            Visit(P("인천 차이나타운", 37.4757, 126.6178, "인천 중구 차이나타운로"), "19:00", "19:15", 70, "저녁", "짜장면 원조집. 탕수육 바삭", 1, listOf(0 to "공화춘 말고 옆집도 맛있음")),
            Visit(P("월미도", 37.4741, 126.5980, "인천 중구 월미문화로"), "20:40", "20:45", 40, "", "디스코팡팡 구경만", 1),
        )))
        trip(null, "단양 드라이브", today.minusDays(62), listOf(listOf(
            Visit(P("단양 도담삼봉", 36.9993, 128.3445, "충북 단양군 매포읍 삼봉로 644-13"), "11:00", "11:10", 40, "", "강 위 봉우리 세 개", 1),
            Visit(P("단양 만천하스카이워크", 36.9850, 128.3510, "충북 단양군 적성면 애곡리"), "13:00", "13:15", 80, "전망", "남한강이 한눈에. 바람 많이 붐", 2),
            Visit(P("단양구경시장", 36.9847, 128.3655, "충북 단양군 단양읍 도전5길"), "15:30", "15:35", 50, "마늘", "흑마늘 닭강정 포장", 1, parking = "시장 공영주차장"),
        )))
        trip(weekend, "태안 꽃지 노을", today.minusDays(83), listOf(listOf(
            Visit(P("안면도 꽃지해수욕장", 36.4970, 126.3360, "충남 태안군 안면읍 승언리"), "17:00", "17:15", 90, "노을", "할미할아비 바위 사이로 해 짐", 2, listOf(1 to "6시 10분쯤이 제일 예뻐")),
            Visit(P("백사장항", 36.5866, 126.3150, "충남 태안군 안면읍 백사장1길"), "19:00", "19:05", 60, "저녁", "대하구이 철이라 1kg", 1),
        )))
        trip(family, "안동 하회마을", today.minusDays(97), listOf(listOf(
            Visit(P("안동 하회마을", 36.5390, 128.5180, "경북 안동시 풍천면 하회종가길 40"), "10:30", "10:45", 110, "", "부용대에서 마을 내려다봄", 2, listOf(3 to "탈춤 공연 2시")),
            Visit(P("안동 찜닭골목", 36.5655, 128.7290, "경북 안동시 번영1길"), "13:30", "13:40", 60, "점심", "찜닭 중자 두 개. 당면 추가", 1),
        )))
        say("일상 기록 더 넣는 중")
        listOf(
            Triple(1L, P("판교 현대백화점", 37.3925, 127.1120, "경기 성남시 분당구 판교역로 146"), Triple("14:00", 90, "주차 B3 F-21. 영화 보고 장보기")),
            Triple(3L, P("정자동 카페거리", 37.3660, 127.1060, "경기 성남시 분당구 정자동"), Triple("10:30", 60, "주말 브런치")),
            Triple(5L, P("분당 중앙공원", 37.3790, 127.1270, "경기 성남시 분당구 성남대로 550"), Triple("07:00", 50, "아침 산책 한 바퀴")),
            Triple(8L, P("율동공원", 37.3755, 127.1500, "경기 성남시 분당구 문정로 72"), Triple("17:30", 70, "노을 보면서 호수 산책")),
            Triple(11L, P("블루핸즈 분당점", 37.3712, 127.1180, "경기 성남시 분당구"), Triple("09:30", 40, "타이어 위치 교환")),
            Triple(15L, P("코스트코 광명점", 37.4237, 126.8829, "경기 광명시 일직로 40"), Triple("11:00", 110, "대용량 장보기. 핫도그 필수")),
            Triple(19L, P("백운호수", 37.3790, 127.0010, "경기 의왕시 학의동"), Triple("19:30", 60, "호수 둘레길 야경")),
            Triple(23L, P("서울숲", 37.5444, 127.0374, "서울 성동구 뚝섬로 273"), Triple("15:00", 80, "사슴 먹이 주기")),
            Triple(30L, P("롯데월드몰", 37.5130, 127.1028, "서울 송파구 올림픽로 300"), Triple("13:00", 120, "서울스카이 올라감. 주차 B4")),
            Triple(38L, P("광교호수공원", 37.2830, 127.0660, "경기 수원시 영통구 광교호수로 165"), Triple("18:00", 60, "자전거 빌려 한 바퀴")),
        ).forEach { (ago, p, t) -> daily(today.minusDays(ago), p, t.first, t.second, t.third, 1) }
        val all = prefs.getStringSet("events", emptySet()).orEmpty() + made
        prefs.edit().putStringSet("events", all.toSet()).apply()
        say("더 채웠어요 · 여행 10개, 일상 10칸" + if (sample > 0) " · 사진은 웹 샘플 ${sample}장(Storage 막힘)" else "")
    }

    /**
     * 시연 메뉴 [폰 계정 넣기] (2026-11-04: 에뮬 폰 앱을 다시 깔아 새 익명 계정이 된 뒤): the phone account with this friend
     * code becomes the owner's friend and a member of every shared group the owner is in, so it sees the trips and
     * records again. Runs with the car's rights (the owner's) — no admin access.
     */
    suspend fun bringIn(code: String) {
        // Only the two people may write a friendship (18장 규칙), not the car: look the account up and put it in the groups.
        val uid = runCatching {
            FirebaseFirestore.getInstance().collection(FirestorePaths.USERS).whereEqualTo("friendCode", Codes.normalize(code)).limit(1).get().await().documents.firstOrNull()?.id
        }.getOrNull() ?: run { say("그 친구 코드를 못 찾았어요"); return }
        val mine = runCatching { FirebaseFirestore.getInstance().collection(FirestorePaths.GROUPS).whereArrayContains("memberIds", ownerUid).get().await().documents }
            .getOrElse { say("그룹을 못 읽었어요 · ${it.message?.take(30)}"); return }
            .filter { it.getBoolean("solo") != true }
        var n = 0
        mine.forEach { g -> if (uid !in (g.get("memberIds") as? List<*>).orEmpty()) runCatching { groups.invite(g.id, uid) }.onSuccess { n++ } }
        say("폰 계정을 그룹 ${n}개에 넣었어요")
    }

    /** Takes away what [run] made: events soft-deleted (as everywhere in the app), the made groups left behind. */
    suspend fun clear() {
        prefs.getStringSet("events", emptySet()).orEmpty().forEach { key ->
            val (g, e) = key.split("/")
            runCatching { calendar.setEventDeleted(g, e, true) }
        }
        prefs.edit().clear().apply()
        say("데모 데이터를 지웠어요")
    }

    /** Already seeded: give every record that has a line but no photo one or two (uploads that failed the first time). */
    private suspend fun fillPhotos() {
        if (photos.isEmpty()) { say("이미 채워져 있어요 · 사진 파일이 없는 빌드예요"); return }
        val db = FirebaseFirestore.getInstance()
        var added = 0
        for (key in prefs.getStringSet("events", emptySet()).orEmpty()) {
            val (g, e) = key.split("/")
            if (e.startsWith("due-")) continue
            val days = runCatching { db.collection("${FirestorePaths.event(g, e)}/days").get().await().documents.map { it.id } }.getOrDefault(emptyList())
            for (d in days) {
                val recs = runCatching { db.collection(FirestorePaths.records(g, e, d)).get().await().toObjects(Record::class.java) }.getOrDefault(emptyList())
                for (r in recs.filter { it.photoUrls.isEmpty() && it.note.isNotBlank() }) {
                    repeat(if (r.note.length > 14) 2 else 1) { nextPhoto()?.let { runCatching { records.addPhoto(g, e, d, r.id, it) }.onSuccess { added++ } } }
                }
            }
        }
        say(if (added > 0) "사진 ${added}장 넣었어요" else "넣을 사진이 없어요 · 다 들어가 있어요")
    }

    // ---------- pieces ----------

    private suspend fun makeFriend(i: Int, name: String): String {
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == "demo$i" } ?: FirebaseApp.initializeApp(context, FirebaseApp.getInstance().options, "demo$i")
        val auth = FirebaseAuth.getInstance(app)
        val uid = auth.currentUser?.uid ?: auth.signInAnonymously().await().user!!.uid
        val db = FirebaseFirestore.getInstance(app)
        db.document(FirestorePaths.user(uid)).set(User(name = name, friendCode = Codes.new())).await()
        // Friends both ways (18장: each side may write the "me" document in the other's list).
        db.document("${FirestorePaths.friends(ownerUid)}/$uid").set(Friend()).await()
        db.document("${FirestorePaths.friends(uid)}/$ownerUid").set(Friend()).await()
        return uid
    }

    private suspend fun group(name: String, others: List<String>): String {
        val gid = groups.createGroup(name, ownerUid)
        others.forEach { groups.invite(gid, it) }
        return gid
    }

    private suspend fun event(gid: String?, e: Event): String {
        val g = gid ?: soloGroupId
        val id = calendar.addEvent(g, e.copy(createdBy = ownerUid))
        return "$g/$id"
    }

    private fun stamp(d: LocalDate, hm: String, plusMin: Int = 0): Timestamp =
        Timestamp(Date.from(d.atTime(LocalTime.parse(hm)).plusMinutes(plusMin.toLong()).atZone(zone).toInstant()))

    /** A past trip: the plan (places, times, memos), every place visited, a record each with notes, photos and comments. */
    private suspend fun trip(gid: String?, title: String, start: LocalDate, days: List<List<Visit>>) {
        val g = gid ?: soloGroupId
        val key = event(gid, Event(categoryId = BuiltInCategory.TRIP.id, title = title, startDate = start.toString(), endDate = start.plusDays(days.size - 1L).toString(), carId = carId))
        made += key
        val eid = key.substringAfter("/")
        val members = gid?.let { runCatching { com.google.firebase.firestore.FirebaseFirestore.getInstance().document(FirestorePaths.group(it)).get().await().get("memberIds") as? List<*> }.getOrNull() }
            ?.filterIsInstance<String>()?.filter { it != ownerUid }.orEmpty()
        days.forEachIndexed { di, visits ->
            val date = start.plusDays(di.toLong())
            val ds = date.toString()
            val ids = visits.map { v -> trips.addStop(g, eid, ds, v.p.place, v.plan, v.memo, ownerUid) }
            visits.forEachIndexed { i, v ->
                trips.setVisited(g, eid, ds, ids[i], true, i + 1)
                v.comments.forEach { (who, text) -> trips.addComment(g, eid, ds, ids[i], text, members.getOrElse(who % maxOf(1, members.size)) { ownerUid }) }
                val rid = records.addRecord(g, eid, ds, Record(
                    place = v.p.place, stopId = ids[i], arrivedAt = stamp(date, v.arrive), leftAt = if (v.stayMin > 0) stamp(date, v.arrive, v.stayMin) else null,
                    note = v.note, createdBy = carId, parkedLat = v.p.lat, parkedLng = v.p.lng, parkingNote = v.parking,
                ))
                repeat(v.photos) { addPhoto(g, eid, ds, rid) }
            }
            trips.setDayStatus(g, eid, ds, DayStatus.DONE)
        }
    }

    /** A trip ahead: just the plan and the group's first comments. */
    private suspend fun tripPlan(gid: String, title: String, date: LocalDate, stops: List<Triple<P, String, String>>, comments: List<Pair<Int, String>>) {
        val key = event(gid, Event(categoryId = BuiltInCategory.TRIP.id, title = title, startDate = date.toString(), endDate = date.toString(), carId = carId))
        made += key
        val eid = key.substringAfter("/")
        val ds = date.toString()
        val ids = stops.map { (p, t, m) -> trips.addStop(gid, eid, ds, p.place, t, m, ownerUid) }
        val members = (com.google.firebase.firestore.FirebaseFirestore.getInstance().document(FirestorePaths.group(gid)).get().await().get("memberIds") as? List<*>)
            ?.filterIsInstance<String>()?.filter { it != ownerUid }.orEmpty()
        comments.forEach { (i, text) -> trips.addComment(gid, eid, ds, ids[i], text, members.getOrElse(i % maxOf(1, members.size)) { ownerUid }) }
    }

    /** One 일상 기록 slot (no plan): where the car stood, a line, a photo. */
    private suspend fun daily(date: LocalDate, p: P, at: String, stay: Int, note: String, photos: Int, parking: String = "") {
        val ds = date.toString()
        val eid = records.ensureDailyLog(soloGroupId, ds, ownerUid)
        made += "$soloGroupId/$eid"
        val rid = records.addRecord(soloGroupId, eid, ds, Record(place = p.place, unplanned = true, arrivedAt = stamp(date, at), leftAt = stamp(date, at, stay), note = note, createdBy = carId, parkedLat = p.lat, parkedLng = p.lng, parkingNote = parking))
        repeat(photos) { addPhoto(soloGroupId, eid, ds, rid) }
    }

    private fun nextPhoto(): ByteArray? = photos.takeIf { it.isNotEmpty() }?.let { it[photoTurn++ % it.size] }

    /** Web sample photos used in place of Storage uploads (counted for the closing line). */
    private var sample = 0
    private var storageRefused = false

    /** One photo on a record: uploaded when Storage takes it, else a web sample URL (test data only). */
    private suspend fun addPhoto(g: String, e: String, d: String, rid: String) {
        val bytes = nextPhoto()
        if (bytes != null && !storageRefused && runCatching { records.addPhoto(g, e, d, rid, bytes) }.isSuccess) return
        storageRefused = true
        runCatching {
            FirebaseFirestore.getInstance().document("${FirestorePaths.records(g, e, d)}/$rid")
                .update("photoUrls", com.google.firebase.firestore.FieldValue.arrayUnion("https://picsum.photos/seed/carplan${rid.take(8)}${photoTurn++}/1200/900")).await()
        }.onSuccess { sample++ }
    }
}
