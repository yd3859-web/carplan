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
                repeat(v.photos) { nextPhoto()?.let { runCatching { records.addPhoto(g, eid, ds, rid, it) } } }
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
        repeat(photos) { nextPhoto()?.let { runCatching { records.addPhoto(soloGroupId, eid, ds, rid, it) } } }
    }

    private fun nextPhoto(): ByteArray? = photos.takeIf { it.isNotEmpty() }?.let { it[photoTurn++ % it.size] }
}
