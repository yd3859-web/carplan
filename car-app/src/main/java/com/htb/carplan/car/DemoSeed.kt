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
 * 시연 메뉴 [오래 쓴 것처럼 채우기] (팀장 2026-10-31): two months of made-up use, so every screen has something real
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
     * 시연 메뉴 [여행 기록 더 채우기] (팀장 2026-11-04: "여행 기록 같은 거 많이, 이것저것"): ten more past trips around the
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

    // ---------- 11월 다시 채우기 ----------

    /** One planned (or, with [unplanned], dropped-in) place of a November trip day: plan, what happened, and the talk under it. */
    private data class Stay(
        val p: P, val plan: String?, val arrive: String, val stayMin: Int, val memo: String = "", val note: String = "", val photos: Int = 0,
        val talk: List<Pair<String, String>> = emptyList(), val parking: String = "", val again: List<String> = emptyList(), val unplanned: Boolean = false,
    )

    /**
     * 시연 메뉴 [11월 다시 채우기] (팀장 2026-11-10: "채워져 있는 기록·일정 다 지우고 진짜 사람이 쓴 것처럼 11월 한 달"):
     * every event of the owner's groups goes away for good (차량 관리 기록·다음 점검만 남김), then November is written
     * as one person's month — trips of four or more places, records with notes and photos on the days already past,
     * plenty of talk from the group under each place, plans ahead with the chat that comes before a trip.
     */
    suspend fun refillNovember() {
        val db = FirebaseFirestore.getInstance()
        say("지난 일정·기록 지우는 중")
        val mine = db.collection(FirestorePaths.GROUPS).whereArrayContains("memberIds", ownerUid).get().await().documents
        var gone = 0
        for (g in mine) gone += wipe(g.id)
        prefs.edit().clear().apply()

        say("같이 다니는 사람 맞추는 중")
        val weekend = circle(mine, "주말 친구들", listOf("민지" to 0, "서연" to 2, "지훈" to 4))
        val carpool = circle(mine, "출근 카풀", listOf("준호" to 1))
        val family = circle(mine, "가족", listOf("엄마" to 3, "아빠" to 5))
        val nov = { d: Int -> LocalDate.of(2026, 11, d) }

        say("11월 지난 여행 넣는 중")
        novTrip(weekend, "가평 단풍 드라이브", nov(1), listOf(listOf(
            Stay(P("가평휴게소 (서울양양)", 37.7140, 127.4010, "경기 가평군 설악면 미사리로"), null, "09:40", 20, note = "호두과자 한 봉지. 아침이라 아직 한산", photos = 1, unplanned = true,
                talk = listOf("서연" to "호두과자 남겨 둬", "지훈" to "이미 다 먹음ㅋㅋ")),
            Stay(P("아침고요수목원", 37.7430, 127.3530, "경기 가평군 상면 수목원로 432"), "10:30", "10:25", 110, "단풍 절정", "하경정원 단풍 진짜 빨갰다. 입구 은행나무 길도 노랗게", 3,
                listOf("민지" to "입장료 1인 11,000원 현장 결제", "서연" to "하경정원은 꼭 위에서 내려다봐야 해", "나" to "주차장 9시 반이면 여유 있대", "지훈" to "사진 백 장 찍을 예정"),
                parking = "제2주차장", again = listOf("민지", "서연")),
            Stay(P("송원 닭갈비", 37.8310, 127.5110, "경기 가평군 가평읍 가화로"), "13:00", "13:10", 60, "점심", "숯불 닭갈비 3인분 + 막국수. 막국수가 더 맛있었음", 2,
                listOf("지훈" to "철판 말고 숯불로 시키자", "민지" to "막국수 비빔이 맛있대", "나" to "웨이팅 있으면 바로 옆 집도 괜찮음")),
            Stay(P("남이섬", 37.7910, 127.5250, "강원 춘천시 남산면 남이섬길 1"), "14:30", "14:40", 120, "배 타고 들어가기", "메타세쿼이아 길 걷다가 자전거. 청설모 엄청 많음", 3,
                listOf("서연" to "배 15분 간격으로 있어", "민지" to "메타세쿼이아 길에서 단체 사진 찍자", "지훈" to "자전거 2인용 빌리면 싸움 남", "서연" to "타조 조심…"),
                parking = "남이섬 선착장 주차장 C", again = listOf("서연")),
            Stay(P("자라섬", 37.8170, 127.5240, "경기 가평군 가평읍 달전리"), "17:00", "17:05", 50, "노을", "강 위로 해 지는 거 보고 출발. 생각보다 추웠다", 2,
                listOf("나" to "5시 반이 일몰이래", "민지" to "담요 챙겨 와")),
            Stay(P("잣향기 카페", 37.7650, 127.3990, "경기 가평군 상면 임초밤안골로"), "18:20", "18:25", 45, "마무리 커피", "잣라떼 고소함. 다들 차에서 꿀잠", 1,
                listOf("지훈" to "운전 고생했다. 라떼는 내가 쏨", "나" to "다음엔 지훈이 운전")),
        )))

        novTrip(weekend, "속초·양양 1박 2일", nov(7), listOf(
            listOf(
                Stay(P("내린천휴게소", 37.9470, 128.1650, "강원 인제군 기린면 서울양양고속도로"), null, "09:50", 20, note = "화장실 + 감자떡. 터널 엄청 길다", photos = 1, unplanned = true,
                    talk = listOf("민지" to "여기 감자떡 맛집")),
                Stay(P("설악산 소공원", 38.1720, 128.4880, "강원 속초시 설악산로 833"), "10:40", "10:50", 150, "케이블카 → 권금성", "케이블카 40분 대기. 권금성 바람 미쳤음. 울산바위 선명하게 보임", 3,
                    listOf("서연" to "케이블카 현장 발권만 돼. 일찍 가야 함", "나" to "10시 반 도착 목표로 출발 7시", "지훈" to "아침은 휴게소에서 먹자", "민지" to "등산화 꼭 신고 와", "서연" to "권금성 위 진짜 추움. 패딩 챙겨"),
                    parking = "소공원 제1주차장 (6,000원)", again = listOf("서연", "지훈")),
                Stay(P("속초 중앙시장", 38.2048, 128.5900, "강원 속초시 중앙로147번길 16"), "13:40", "13:50", 70, "점심 + 닭강정", "오징어순대, 아바이순대, 닭강정 반반. 줄은 닭강정이 제일 길었음", 2,
                    listOf("지훈" to "만석닭강정 vs 중앙닭강정 투표 ㄱ", "민지" to "만석!", "서연" to "중앙이 덜 달아", "나" to "둘 다 사면 되지", "민지" to "아바이순대는 지하 1층")),
                Stay(P("영금정", 38.2140, 128.6010, "강원 속초시 영금정로 43"), "15:30", "15:35", 40, "", "정자까지 다리 건너서 파도 구경. 물보라가 정자까지 튐", 2,
                    listOf("서연" to "여기 일출 명소래. 내일 아침에 다시 올까?", "지훈" to "난 못 일어남")),
                Stay(P("속초해수욕장", 38.1900, 128.6030, "강원 속초시 해오름로 186"), "16:30", "16:40", 60, "대관람차", "속초아이 타고 해변 산책. 바람이 차가웠다", 2,
                    listOf("민지" to "대관람차 1인 15,000원", "나" to "해 질 때 타면 좋을 듯")),
                Stay(P("대포항", 38.1745, 128.6070, "강원 속초시 대포항길 22"), "18:30", "18:35", 90, "저녁 회", "모둠회 중자 + 새우튀김. 숙소 가는 길에 들름", 2,
                    listOf("지훈" to "새우튀김 골목 꼭", "서연" to "회는 2층 초장집에서 먹자", "민지" to "여기서 숙소 15분")),
            ),
            listOf(
                Stay(P("낙산사", 38.1240, 128.6290, "강원 양양군 강현면 낙산사로 100"), "09:30", "09:40", 80, "해수관음상", "의상대에서 바다. 아침이라 조용해서 좋았다", 2,
                    listOf("서연" to "의상대 꼭 들러", "나" to "주차장에서 좀 걸어", "민지" to "입장료 없어졌대")),
                Stay(P("하조대", 38.0300, 128.7190, "강원 양양군 현북면 하조대길"), "11:20", "11:25", 40, "", "소나무 + 바위 + 바다. 등대까지 걸어감", 1,
                    listOf("지훈" to "애국가 나오는 그 소나무")),
                Stay(P("서피비치", 38.0210, 128.7180, "강원 양양군 현북면 하조대해안길 119"), "12:10", "12:15", 80, "점심 + 바다", "해변 펍에서 피시앤칩스. 서핑하는 사람들 구경", 2,
                    listOf("민지" to "다음 여름엔 서핑 배우자", "지훈" to "난 구경만", "서연" to "빈백에 누워 있고 싶다")),
                Stay(P("주문진수산시장", 37.8930, 128.8310, "강원 강릉시 주문진읍 시장길"), "14:30", "14:40", 50, "건어물 쇼핑", "오징어·황태 포장. 아이스박스 챙긴 거 신의 한 수", 2,
                    listOf("나" to "아이스박스 트렁크에 있어", "서연" to "엄마가 황태 사 오래", "지훈" to "집 가는 길 막히기 전에 3시엔 출발하자"),
                    parking = "주문진 공영주차장", again = listOf("서연")),
            ),
        ))

        say("11월 일상 기록 넣는 중")
        novDaily(nov(2), P("판교 테크노밸리 주차장", 37.4020, 127.1080, "경기 성남시 분당구 판교로"), "08:50", 540, "월요일. 카풀 첫날 준호 태우고 출근")
        novDaily(nov(3), P("셀프세차장 분당점", 37.3602, 127.1215, "경기 성남시 분당구 탄천로"), "20:10", 40, "가평 다녀온 흙 세차. 휠까지 닦음", 1)
        novDaily(nov(4), P("이마트 분당점", 37.3510, 127.1100, "경기 성남시 분당구 황새울로 300"), "19:20", 50, "속초 갈 간식이랑 아이스박스", 1, "B2 C-14")
        novDaily(nov(6), P("정자동 카페거리", 37.3660, 127.1060, "경기 성남시 분당구 정자일로"), "12:30", 60, "점심 먹고 커피. 내일 여행 일정 마지막 정리", 1)
        novDaily(nov(9), P("율동공원", 37.3755, 127.1500, "경기 성남시 분당구 문정로 72"), "18:00", 60, "여행 후유증. 호수 한 바퀴 걷고 옴", 2)
        novDaily(nov(9), P("분당 서울대병원 주차장", 37.3520, 127.1240, "경기 성남시 분당구 구미로173번길 82"), "10:00", 90, "아빠 정기 검진 모셔다 드림", 0, "지하 3층 D-07")
        made += event(family, Event(title = "아빠 생신 저녁", startDate = nov(5).toString(), endDate = nov(5).toString(), allDay = false, startTime = "19:00",
            place = P("수원 본가 갈비", 37.2760, 127.0290, "경기 수원시 팔달구 중부대로").place, memo = "케이크는 내가 픽업"))

        say("11월 앞으로의 일정 넣는 중")
        made += event(carpool, Event(title = "출근", startDate = nov(2).toString(), endDate = nov(2).toString(), allDay = false, startTime = "08:20",
            place = P("판교역", 37.3948, 127.1112, "경기 성남시 분당구 판교역로 160").place,
            repeat = Repeat.weeklyRule(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))))
        made += event(null, Event(title = "치과", startDate = nov(12).toString(), endDate = nov(12).toString(), allDay = false, startTime = "15:00",
            place = P("서현 미소치과", 37.3850, 127.1235, "경기 성남시 분당구 서현로").place, memo = "스케일링"))
        made += event(null, Event(title = "팀 회식", startDate = nov(13).toString(), endDate = nov(13).toString(), allDay = false, startTime = "19:00",
            place = P("판교 화로구이", 37.3955, 127.1090, "경기 성남시 분당구 대왕판교로").place, memo = "차는 회사에 두고 대리"))
        made += event(null, Event(categoryId = BuiltInCategory.CAR.id, title = "엔진오일 교체 예약", startDate = nov(17).toString(), endDate = nov(17).toString(), allDay = false, startTime = "10:00",
            place = P("블루핸즈 분당점", 37.3712, 127.1180, "경기 성남시 분당구 성남대로").place))
        made += event(weekend, Event(title = "민지 생일", startDate = nov(19).toString(), endDate = nov(19).toString(), memo = "선물 같이 사기 (지훈이 정리)"))
        made += event(null, Event(title = "대학 동기 모임", startDate = nov(25).toString(), endDate = nov(25).toString(), allDay = false, startTime = "19:00",
            place = P("강남역 11번 출구", 37.4980, 127.0276, "서울 강남구 강남대로").place))

        novPlan(weekend, "전주·군산 1박 2일", nov(14), listOf(
            listOf(
                Stay(P("전주한옥마을", 35.8150, 127.1530, "전북 전주시 완산구 기린대로 99"), "11:00", "", 0, "한복 빌려 입기", talk = listOf(
                    "민지" to "한복 대여 예약했어! 11시 반 4명", "서연" to "머리 땋아 주는 데도 있대", "지훈" to "난 갓 쓸래", "나" to "주차는 한옥마을 공영주차장 (치명자산 쪽)")),
                Stay(P("경기전", 35.8153, 127.1497, "전북 전주시 완산구 태조로 44"), "12:30", "", 0, "", talk = listOf(
                    "서연" to "한복 입으면 경기전 무료!", "민지" to "대나무숲에서 사진")),
                Stay(P("베테랑 칼국수", 35.8170, 127.1530, "전북 전주시 완산구 경기전길 135"), "13:30", "", 0, "점심", talk = listOf(
                    "지훈" to "칼국수 + 만두 무조건", "나" to "줄 길면 바로 옆 비빔밥집 플랜 B", "서연" to "콩국수는 여름만 해")),
                Stay(P("자만벽화마을", 35.8160, 127.1580, "전북 전주시 완산구 교동"), "15:00", "", 0, "골목 카페", talk = listOf(
                    "민지" to "오르막이라 편한 신발", "서연" to "꼭대기 카페에서 한옥마을 보여")),
                Stay(P("남부시장 야시장", 35.8117, 127.1460, "전북 전주시 완산구 풍남문1길 19-3"), "19:00", "", 0, "야시장", talk = listOf(
                    "지훈" to "금·토만 열어. 우리 토요일이라 딱", "민지" to "가맥 가자 가맥", "나" to "숙소는 걸어서 10분")),
            ),
            listOf(
                Stay(P("군산 초원사진관", 35.9860, 126.7110, "전북 군산시 구영2길 12-1"), "10:30", "", 0, "8월의 크리스마스", talk = listOf(
                    "서연" to "영화 촬영지! 여기서 사진 꼭", "나" to "전주에서 1시간")),
                Stay(P("이성당", 35.9873, 126.7115, "전북 군산시 중앙로 177"), "11:10", "", 0, "단팥빵·야채빵", talk = listOf(
                    "민지" to "야채빵 줄 따로 있대", "지훈" to "단팥빵 10개 살 거야", "엄마" to "엄마 것도 두 개만 사 와")),
                Stay(P("경암동 철길마을", 35.9780, 126.7360, "전북 군산시 경촌4길 14"), "13:00", "", 0, "쫀드기 구워 먹기", talk = listOf(
                    "서연" to "교복 대여도 있어ㅋㅋ")),
                Stay(P("새만금방조제", 35.8100, 126.5600, "전북 군산시 옥도면 새만금로"), "15:00", "", 0, "드라이브 후 귀가", talk = listOf(
                    "나" to "방조제 쭉 달리고 서해안고속도로로 올라가자", "지훈" to "운전 교대할게")),
            ),
        ))
        novPlan(family, "양평 가족 나들이", nov(21), listOf(listOf(
            Stay(P("두물머리", 37.5345, 127.3170, "경기 양평군 양서면 양수리"), "10:00", "", 0, "산책", talk = listOf(
                "엄마" to "아침 물안개 보러 일찍 가자", "아빠" to "연핫도그 먹어야지", "나" to "8시 반에 데리러 갈게요")),
            Stay(P("세미원", 37.5384, 127.3112, "경기 양평군 양서면 양수로 93"), "11:00", "", 0, "배다리", talk = listOf(
                "엄마" to "배다리 건너면 두물머리랑 이어져")),
            Stay(P("양평 옥천냉면", 37.5170, 127.4930, "경기 양평군 옥천면 옥천길 92"), "12:30", "", 0, "점심", talk = listOf(
                "아빠" to "완자가 유명해", "나" to "11월이라 온면도 있대")),
            Stay(P("용문사", 37.5370, 127.5810, "경기 양평군 용문면 용문산로 782"), "14:30", "", 0, "은행나무", talk = listOf(
                "엄마" to "천 년 넘은 은행나무 꼭 보고 싶었어", "아빠" to "잎 다 떨어졌으려나", "나" to "주말에 떨어진 잎도 예쁘대요")),
            Stay(P("양평 물맑은시장", 37.4920, 127.4910, "경기 양평군 양평읍 양평시장길"), "16:30", "", 0, "장 보고 귀가", talk = listOf(
                "엄마" to "3·8일 장날인데 21일은 아니네", "아빠" to "그래도 상설시장은 열어")),
        )))
        novPlan(weekend, "부산 1박 2일", nov(28), listOf(
            listOf(
                Stay(P("해동용궁사", 35.1883, 129.2233, "부산 기장군 기장읍 용궁길 86"), "11:00", "", 0, "바다 옆 절", talk = listOf(
                    "서연" to "주차 전쟁이래. 일찍 도착하자", "나" to "새벽 6시 출발 어때", "지훈" to "…살려줘")),
                Stay(P("기장시장", 35.2443, 129.2187, "부산 기장군 기장읍 읍내로 104"), "12:30", "", 0, "대게 점심", talk = listOf(
                    "민지" to "대게 시세 보고 정하자", "지훈" to "나는 곰장어")),
                Stay(P("해운대 블루라인파크", 35.1590, 129.1720, "부산 해운대구 청사포로 116"), "14:30", "", 0, "스카이캡슐", talk = listOf(
                    "민지" to "스카이캡슐 예약 필수! 내가 할게", "서연" to "4인 1캡슐", "나" to "미포 정거장 주차")),
                Stay(P("더베이101", 35.1565, 129.1517, "부산 해운대구 동백로 52"), "18:00", "", 0, "야경", talk = listOf(
                    "서연" to "마린시티 야경 여기서 보는 게 최고")),
                Stay(P("광안리해수욕장", 35.1532, 129.1186, "부산 수영구 광안해변로 219"), "20:00", "", 0, "드론쇼", talk = listOf(
                    "지훈" to "토요일 드론쇼 8시·10시", "민지" to "숙소 광안리 오션뷰로 잡았어")),
            ),
            listOf(
                Stay(P("흰여울문화마을", 35.0780, 129.0450, "부산 영도구 영선동4가"), "10:00", "", 0, "바다 골목", talk = listOf(
                    "서연" to "영화 변호인 촬영지", "민지" to "골목 카페 하나 찍어 둠")),
                Stay(P("태종대", 35.0530, 129.0870, "부산 영도구 전망로 24"), "11:30", "", 0, "다누비 열차", talk = listOf(
                    "나" to "다누비 열차 타고 한 바퀴")),
                Stay(P("자갈치시장", 35.0967, 129.0305, "부산 중구 자갈치해안로 52"), "13:00", "", 0, "회 점심", talk = listOf(
                    "지훈" to "2층 초장집", "민지" to "꼼장어 구이도")),
                Stay(P("감천문화마을", 35.0975, 129.0106, "부산 사하구 감내2로 203"), "15:00", "", 0, "마지막 산책 후 귀가", talk = listOf(
                    "서연" to "어린왕자 줄 길면 패스", "나" to "5시엔 출발해야 10시 전 도착")),
            ),
        ))

        prefs.edit().putBoolean("done", true).putStringSet("events", made.toSet()).putStringSet("groups", setOf(weekend, carpool, family)).apply()
        say("11월 채웠어요 · 지운 일정 ${gone}개 · 지난 여행 2개, 앞으로 여행 3개, 일상 기록 6칸" + if (sample > 0) " · 사진은 웹 샘플" else "")
    }

    /** Erases every event of [gid] for good — its days and everything under them — except 차량 관리 records and next-dues. */
    private suspend fun wipe(gid: String): Int {
        val db = FirebaseFirestore.getInstance()
        val events = db.collection(FirestorePaths.events(gid)).get().await().documents
        var n = 0
        for (e in events) {
            if (e.id.startsWith("due-") || e.get("maintenance") != null) continue
            val listed = runCatching { db.collection("${FirestorePaths.event(gid, e.id)}/days").get().await().documents.map { it.id } }.getOrDefault(emptyList())
            // Records of 일상 기록 hang under days that have no document of their own: walk the event's dates too.
            val from = runCatching { LocalDate.parse(e.getString("startDate")) }.getOrNull()
            val to = runCatching { LocalDate.parse(e.getString("endDate")) }.getOrNull() ?: from
            val span = if (from != null && to != null && !to.isBefore(from) && to.toEpochDay() - from.toEpochDay() < 31)
                generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { it.toString() }.toList() else emptyList()
            for (d in (listed + span).distinct()) {
                for (sub in listOf("stops", "comments", "records", "tracks", "pickups")) deleteAll(db, "${FirestorePaths.day(gid, e.id, d)}/$sub")
                db.document(FirestorePaths.day(gid, e.id, d)).delete().await()
            }
            e.reference.delete().await()
            n++
        }
        // The group's "가 봄 · 또 갈래요" memos came from those records.
        deleteAll(db, FirestorePaths.places(gid))
        return n
    }

    private suspend fun deleteAll(db: FirebaseFirestore, path: String) {
        val docs = db.collection(path).get().await().documents
        docs.chunked(400).forEach { part -> db.runBatch { b -> part.forEach { b.delete(it.reference) } }.await() }
    }

    /** The owner's group called [name] (made when missing) with these people in it, as made-up friend accounts. */
    private suspend fun circle(mine: List<com.google.firebase.firestore.DocumentSnapshot>, name: String, people: List<Pair<String, Int>>): String {
        val gid = mine.firstOrNull { it.getString("name") == name && it.getString("forEvent") == null }?.id ?: groups.createGroup(name, ownerUid)
        val have = names(gid).keys
        people.forEach { (n, i) -> if (n !in have) groups.invite(gid, makeFriend(i, n)) }
        return gid
    }

    /** name → uid of the group's members; the owner is "나". */
    private suspend fun names(gid: String): Map<String, String> {
        val db = FirebaseFirestore.getInstance()
        val ids = (db.document(FirestorePaths.group(gid)).get().await().get("memberIds") as? List<*>)?.filterIsInstance<String>().orEmpty()
        val out = mutableMapOf("나" to ownerUid)
        ids.filter { it != ownerUid }.forEach { uid -> runCatching { db.document(FirestorePaths.user(uid)).get().await().getString("name") }.getOrNull()?.let { out.putIfAbsent(it, uid) } }
        return out
    }

    /** A November trip already driven: plan, visited, a record per place (unplanned ones without a slot), talk, 또 갈래요. */
    private suspend fun novTrip(gid: String, title: String, start: LocalDate, days: List<List<Stay>>) {
        val key = event(gid, Event(categoryId = BuiltInCategory.TRIP.id, title = title, startDate = start.toString(), endDate = start.plusDays(days.size - 1L).toString(), carId = carId))
        made += key
        val eid = key.substringAfter("/")
        val who = names(gid)
        days.forEachIndexed { di, stays ->
            val date = start.plusDays(di.toLong())
            val ds = date.toString()
            val ids = stays.map { s -> if (s.unplanned) null else trips.addStop(gid, eid, ds, s.p.place, s.plan, s.memo, ownerUid) }
            var visited = 0
            stays.forEachIndexed { i, s ->
                val sid = ids[i]
                if (sid != null) trips.setVisited(gid, eid, ds, sid, true, ++visited)
                val rid = records.addRecord(gid, eid, ds, Record(
                    place = s.p.place, stopId = sid, unplanned = s.unplanned, arrivedAt = stamp(date, s.arrive), leftAt = stamp(date, s.arrive, s.stayMin),
                    note = s.note, createdBy = carId, parkedLat = s.p.lat, parkedLng = s.p.lng, parkingNote = s.parking,
                ))
                repeat(s.photos) { addPhoto(gid, eid, ds, rid) }
                // Talk hangs under a slot; a dropped-in place has none, so its talk goes under the next planned one.
                val under = sid ?: ids.drop(i).firstOrNull { it != null }
                if (under != null) s.talk.forEach { (n, text) -> trips.addComment(gid, eid, ds, under, text, who[n] ?: ownerUid) }
                s.again.mapNotNull { who[it] }.forEach { uid -> records.setAgain(gid, eid, ds, Record(id = rid, place = s.p.place), uid, true) }
            }
            trips.setDayStatus(gid, eid, ds, DayStatus.DONE)
        }
    }

    /** A November trip ahead: the slots with planned times and memos, and the group talking it over. */
    private suspend fun novPlan(gid: String, title: String, start: LocalDate, days: List<List<Stay>>) {
        val key = event(gid, Event(categoryId = BuiltInCategory.TRIP.id, title = title, startDate = start.toString(), endDate = start.plusDays(days.size - 1L).toString(), carId = carId))
        made += key
        val eid = key.substringAfter("/")
        val who = names(gid)
        days.forEachIndexed { di, stays ->
            val ds = start.plusDays(di.toLong()).toString()
            stays.forEach { s ->
                val sid = trips.addStop(gid, eid, ds, s.p.place, s.plan, s.memo, ownerUid)
                // 엄마 on a friends' trip (이성당 빵 부탁) is not in the group: the line is kept as the owner relaying it.
                s.talk.forEach { (n, text) -> trips.addComment(gid, eid, ds, sid, if (n in who) text else "$n: $text", who[n] ?: ownerUid) }
            }
        }
    }

    /** One 일상 기록 slot on a November day. */
    private suspend fun novDaily(date: LocalDate, p: P, at: String, stay: Int, note: String, photos: Int = 0, parking: String = "") {
        val ds = date.toString()
        val eid = records.ensureDailyLog(soloGroupId, ds, ownerUid)
        made += "$soloGroupId/$eid"
        val rid = records.addRecord(soloGroupId, eid, ds, Record(place = p.place, unplanned = true, arrivedAt = stamp(date, at), leftAt = stamp(date, at, stay), note = note, createdBy = carId, parkedLat = p.lat, parkedLng = p.lng, parkingNote = parking))
        repeat(photos) { addPhoto(soloGroupId, eid, ds, rid) }
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
        var failed: Throwable? = null
        for (key in prefs.getStringSet("events", emptySet()).orEmpty()) {
            val (g, e) = key.split("/")
            if (e.startsWith("due-")) continue
            val days = runCatching { db.collection("${FirestorePaths.event(g, e)}/days").get().await().documents.map { it.id } }.getOrDefault(emptyList())
            for (d in days) {
                val recs = runCatching { db.collection(FirestorePaths.records(g, e, d)).get().await().toObjects(Record::class.java) }.getOrDefault(emptyList())
                for (r in recs.filter { it.photoUrls.isEmpty() && it.note.isNotBlank() }) {
                    repeat(if (r.note.length > 14) 2 else 1) { nextPhoto()?.let { runCatching { records.addPhoto(g, e, d, r.id, it) }.onSuccess { added++ }.onFailure { err -> failed = failed ?: err } } }
                }
            }
        }
        failed?.let { android.util.Log.w("DemoSeed", "photo upload failed", it) }
        say(when {
            added > 0 -> "사진 ${added}장 넣었어요"
            failed != null -> "사진 못 올렸어요 · ${failed?.message?.take(60)}"
            else -> "넣을 사진이 없어요 · 다 들어가 있어요"
        })
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
        if (bytes != null && !storageRefused && runCatching { records.addPhoto(g, e, d, rid, bytes) }.onFailure { android.util.Log.w("DemoSeed", "photo upload failed", it) }.isSuccess) return
        storageRefused = true
        runCatching {
            FirebaseFirestore.getInstance().document("${FirestorePaths.records(g, e, d)}/$rid")
                .update("photoUrls", com.google.firebase.firestore.FieldValue.arrayUnion("https://picsum.photos/seed/carplan${rid.take(8)}${photoTurn++}/1200/900")).await()
        }.onSuccess { sample++ }
    }
}
