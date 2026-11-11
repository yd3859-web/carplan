package com.htb.carplan.phone.vote

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.htb.carplan.shared.data.DartRepository
import com.htb.carplan.shared.data.GroupRepository
import com.htb.carplan.shared.data.Regions
import com.htb.carplan.shared.data.SessionRepository
import com.htb.carplan.shared.data.TripRepository
import com.htb.carplan.shared.data.VoteRepository
import com.htb.carplan.shared.model.Dart
import com.htb.carplan.shared.model.DartScope
import com.htb.carplan.shared.model.Group
import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.model.Vote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/** 5장 투표 + 6장 다트 for one group (and, optionally, the trip day they belong to). */
class VoteDartViewModel(private val groupId: String, private val eventId: String?, private val date: String?) : ViewModel() {
    private val votes = VoteRepository()
    private val darts = DartRepository()
    private val groups = GroupRepository()
    private val trips = TripRepository()
    val uid = MutableStateFlow<String?>(null)
    val me: StateFlow<String?> = uid.asStateFlow()
    val message = MutableStateFlow<String?>(null)

    val group: StateFlow<Group?> = groups.observeGroup(groupId).catch { emit(null) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val listLoaded = MutableStateFlow(false)
    val list: StateFlow<List<Vote>> = votes.observe(groupId).catch { message.value = it.message; emit(emptyList()) }
        .onEach { listLoaded.value = true }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val dart: StateFlow<Dart?> = darts.observeLatest(groupId).catch { emit(null) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init { viewModelScope.launch { runCatching { SessionRepository().ensureSession() }.onSuccess { uid.value = it.uid } } }

    fun create(question: String, options: List<String>, places: List<Place?>) = act {
        votes.create(groupId, question.ifBlank { "점심 뭐 먹지?" }, options, places, eventId, date, uid.value.orEmpty())
    }

    fun cast(voteId: String, option: Int) = act {
        val members = group.value?.memberIds?.size ?: 1
        votes.cast(groupId, voteId, uid.value ?: return@act, option, members)
    }

    fun close(voteId: String) = act { votes.close(groupId, voteId) }

    /** 5장 4: the winning place becomes a stop of the trip day. */
    fun addWinnerAsStop(place: Place) = act {
        val e = eventId ?: return@act; val d = date ?: return@act
        trips.addStop(groupId, e, d, place, null, "투표로 정함", uid.value.orEmpty())
        message.value = "${com.htb.carplan.shared.text.Josa.eul(place.name)} 칸으로 넣었어요"
    }

    /** 6장: one throw. NEAR needs the phone's position; REGION a 시·도 name. */
    fun throwDart(context: Context, scope: DartScope, centerLat: Double?, centerLng: Double?, region: String?) = act {
        loadRegionsOnce(context)
        val current = dart.value
        val sameRoom = current != null && current.scope == scope && current.region == region && current.eventId == eventId
        val pick = Regions.pick(scope, centerLat, centerLng, region) ?: run { message.value = "그 범위엔 동네가 없어요"; return@act }
        darts.throwDart(groupId, if (sameRoom) current!!.id else null, scope, centerLat, centerLng, region, pick, if (sameRoom) current!!.throwCount + 1 else 1, uid.value.orEmpty(), eventId, date)
    }

    /** 6장: the dart landed at (lat, lng) on the board → the nearest 동네 in the scope is the result. */
    fun throwDartAt(context: Context, scope: DartScope, centerLat: Double?, centerLng: Double?, region: String?, lat: Double, lng: Double, radiusKm: Double = 100.0) = act {
        loadRegionsOnce(context)
        val current = dart.value
        val sameRoom = current != null && current.scope == scope && current.region == region && current.eventId == eventId
        val pick = Regions.nearest(lat, lng, Regions.pool(scope, centerLat, centerLng, region, radiusKm)) ?: run { message.value = "그 범위엔 동네가 없어요"; return@act }
        darts.throwDart(groupId, if (sameRoom) current!!.id else null, scope, centerLat, centerLng, region, pick, if (sameRoom) current!!.throwCount + 1 else 1, uid.value.orEmpty(), eventId, date)
    }

    /**
     * Q57: places of one kind around the dart's 동네 — 동 3 km, 읍·면 10 km (they are wide), twice that once if empty.
     * Kept per throw so pressing the same chip again costs nothing (API 한도).
     */
    private val spotCache = HashMap<String, List<Place>>()
    suspend fun dartSpots(d: Dart, category: com.htb.carplan.shared.net.KakaoCategory): List<Place> {
        val key = "${d.id}#${d.throwCount}#${category.code}"
        spotCache[key]?.let { return it }
        if (!com.htb.carplan.phone.trip.kakao.available) return emptyList()
        val wide = d.resultName.trim().endsWith("읍") || d.resultName.trim().endsWith("면")
        val r = if (wide) 10_000 else 3_000
        val found = runCatching {
            com.htb.carplan.phone.trip.kakao.searchCategory(category, d.resultLat, d.resultLng, radiusMeters = r, size = 5)
                .ifEmpty { com.htb.carplan.phone.trip.kakao.searchCategory(category, d.resultLat, d.resultLng, radiusMeters = r * 2, size = 5) }
        }.getOrElse { message.value = it.message; return emptyList() }
        val ranked = com.htb.carplan.shared.calendar.PlaceRank.rank(found, memos())
        spotCache[key] = ranked
        return ranked
    }

    /** Q58: my groups' 또 갈래요 · 가 봄, read once per window. */
    private var memoCache: Map<String, com.htb.carplan.shared.model.PlaceMemo>? = null
    suspend fun memos(): Map<String, com.htb.carplan.shared.model.PlaceMemo> = memoCache ?: runCatching {
        val me = uid.value ?: return emptyMap()
        val ids = com.htb.carplan.shared.data.CalendarRepository().observeGroups(me).first().map { it.id }
        com.htb.carplan.shared.data.RecordRepository().placeMemos(ids)
    }.getOrDefault(emptyMap()).also { memoCache = it }

    /** A recommended place from the dart → a stop (instead of the 동네's middle). */
    fun addPlaceAsStop(place: Place, d: Dart) = act {
        val e = eventId ?: return@act
        trips.addStop(groupId, e, date ?: java.time.LocalDate.now().toString(), place, null, "다트 · " + d.resultName.split(" ").last(), uid.value.orEmpty())
        message.value = "${com.htb.carplan.shared.text.Josa.eul(place.name)} 칸으로 넣었어요"
    }

    /** 다트 동네의 대표 코스(기억장치)를 통째로 칸으로 (팀장 2026-11-09 "다트로 지역 뽑히면 추천도"). */
    fun addCourseAsStops(c: com.htb.carplan.shared.calendar.Course, d: Dart) = act {
        val e = eventId ?: return@act
        var put = 0
        c.places.take(5).forEach { name ->
            val p = if (com.htb.carplan.phone.trip.kakao.available)
                runCatching { com.htb.carplan.phone.trip.kakao.searchKeyword("${c.word} $name", size = 1).firstOrNull() }.getOrNull() ?: return@forEach
            else Place(name = name)
            trips.addStop(groupId, e, date ?: java.time.LocalDate.now().toString(), p, null, "다트 · ${c.word} 코스", uid.value.orEmpty())
            put++
        }
        message.value = if (put > 0) "${c.word} 코스 ${put}곳을 칸으로 넣었어요" else "코스 장소를 찾지 못했어요"
    }

    /** 다트가 꽂힌 동네의 하루 코스: 손으로 정리한 대표 코스가 있으면 그것, 없으면 그 자리 둘레에서 바로 짠다. */
    data class DartCourse(val title: String, val places: List<Place>, val curated: Boolean, val note: String = "")
    private val courseCache = HashMap<String, DartCourse?>()

    /**
     * 즉석 코스 (2026-11-10): 어디에 꽂히든 그 동네 코스가 나오게. 대표 코스(기억장치)가 있는 시·군이면 그 장소들을 카카오로
     * 확인해 쓰고, 없으면 관광지·식당·카페를 둘레에서 찾아 관광지 → 점심 → 관광지 → 카페로 엮는다 (shared [DayCourse]).
     * 던질 때마다 카카오 3번, 같은 결과는 기억해 다시 부르지 않는다 (API 한도). 키가 없으면 대표 코스 이름만.
     */
    suspend fun dartCourse(context: Context, d: Dart): DartCourse? {
        val key = "${d.id}#${d.throwCount}"
        if (courseCache.containsKey(key)) return courseCache[key]
        val kakao = com.htb.carplan.phone.trip.kakao
        val curated = com.htb.carplan.phone.record.NextTripLoader.courses(context).firstOrNull { c -> d.resultName.split(" ").any { t -> t.startsWith(c.word) } }
        val result = if (curated != null) {
            val places = if (kakao.available) curated.places.take(5).mapNotNull { name ->
                runCatching { kakao.searchKeyword("${curated.word} $name", size = 1).firstOrNull() }.getOrNull()
            } else curated.places.take(5).map { Place(name = it) }
            DartCourse("${curated.word} 대표 코스", places, curated = true, note = curated.note)
        } else if (kakao.available) runCatching {
            suspend fun around(c: com.htb.carplan.shared.net.KakaoCategory, r: Int) =
                kakao.searchCategory(c, d.resultLat, d.resultLng, radiusMeters = r, size = 6)
            val sights = around(com.htb.carplan.shared.net.KakaoCategory.SIGHT, 10_000).ifEmpty { around(com.htb.carplan.shared.net.KakaoCategory.SIGHT, 20_000) }
            val foods = around(com.htb.carplan.shared.net.KakaoCategory.FOOD, 10_000)
            val cafes = around(com.htb.carplan.shared.net.KakaoCategory.CAFE, 10_000)
            val day = com.htb.carplan.shared.calendar.DayCourse.build(d.resultLat, d.resultLng, sights, foods, cafes)
            DartCourse("${d.resultName.split(" ").last()} 하루 코스", day, curated = false)
        }.getOrNull() else null
        val kept = result?.takeIf { it.places.isNotEmpty() }
        courseCache[key] = kept
        return kept
    }

    /** [이 코스로 여행하기]: 코스 장소들을 차례로 칸으로. */
    fun addDartCourse(course: DartCourse, d: Dart) = act {
        val e = eventId ?: return@act
        course.places.forEach { p -> trips.addStop(groupId, e, date ?: java.time.LocalDate.now().toString(), p, null, "다트 · " + d.resultName.split(" ").last(), uid.value.orEmpty()) }
        message.value = "${course.places.size}곳을 칸으로 넣었어요"
    }

    /** The board needs the full 읍·면·동 list to frame the scope. */
    fun ensureRegions(context: Context) = loadRegionsOnce(context)

    /** 다트 결과를 이 그룹 채팅방에 카드로 (A안 2026-11-03). */
    fun shareDart(d: Dart) = act {
        com.htb.carplan.shared.data.ChatRepository().shareDart(groupId, d, uid.value ?: return@act)
        message.value = "채팅에 올렸어요"
    }

    fun addDartAsStop(d: Dart) = act {
        val e = eventId ?: return@act
        val parts = d.resultName.split(" ")
        trips.addStop(groupId, e, date ?: java.time.LocalDate.now().toString(), Place(name = parts.last(), lat = d.resultLat, lng = d.resultLng, address = d.resultName), null, "다트 · " + parts.dropLast(1).drop(1).joinToString(" "), uid.value.orEmpty())
        message.value = "${com.htb.carplan.shared.text.Josa.eul(parts.last())} 칸으로 넣었어요"
    }

    private var regionsLoaded = false
    private fun loadRegionsOnce(context: Context) {
        if (regionsLoaded) return
        regionsLoaded = true
        runCatching { context.assets.open("regions.csv").bufferedReader().readLines() }.onSuccess { Regions.load(it) }
    }

    private fun act(block: suspend () -> Unit) { viewModelScope.launch { runCatching { block() }.onFailure { message.value = it.message } } }
}
