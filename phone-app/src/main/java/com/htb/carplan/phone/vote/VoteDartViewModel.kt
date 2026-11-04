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
