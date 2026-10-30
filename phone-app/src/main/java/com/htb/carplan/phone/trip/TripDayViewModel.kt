package com.htb.carplan.phone.trip

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.htb.carplan.phone.BuildConfig
import com.htb.carplan.shared.calendar.RouteMath
import com.htb.carplan.shared.calendar.Schedule
import com.htb.carplan.shared.calendar.RouteMath.hasCoords
import com.htb.carplan.shared.data.CalendarRepository
import com.htb.carplan.shared.data.GroupRepository
import com.htb.carplan.shared.data.RecordRepository
import com.htb.carplan.shared.data.PickupRepository
import com.htb.carplan.shared.model.Pickup
import com.htb.carplan.shared.model.PickupStatus
import com.htb.carplan.shared.model.Record
import com.htb.carplan.shared.model.Group
import com.htb.carplan.shared.data.SessionRepository
import com.htb.carplan.shared.data.TripRepository
import com.htb.carplan.shared.model.Comment
import com.htb.carplan.shared.model.Event
import com.htb.carplan.shared.model.TripDay
import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.model.Stop
import com.htb.carplan.shared.net.KakaoApi
import com.htb.carplan.shared.net.KakaoCategory
import com.htb.carplan.shared.net.KakaoKeyMissing
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Where a trip day lives in Firestore. */
data class TripRef(val groupId: String, val eventId: String, val date: String)

/** One drive between two consecutive stops; [exact] = from Kakao Mobility, else a straight-line guess. */
data class LegInfo(val seconds: Int, val meters: Int, val exact: Boolean)

/** Kakao key present → real search; absent → the screen offers typing a name instead. */
val kakao: KakaoApi by lazy { KakaoApi(BuildConfig.KAKAO_REST_KEY) }

/**
 * 3장 여행 하루: the stops of one trip day, edited live by every member.
 * Order edits are applied locally first (drag feels instant) and then written back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripDayViewModel(private val groupId: String, private val eventId: String, initialDate: String) : ViewModel() {
    private val sessions = SessionRepository()
    private val calendar = CalendarRepository()
    private val trips = TripRepository()

    val uid = MutableStateFlow<String?>(null)
    private val _date = MutableStateFlow(initialDate)
    val date: StateFlow<String> = _date.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    fun setError(message: String?) { _error.value = message }
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val event: StateFlow<Event?> = calendar.observeEvent(groupId, eventId)
        .catch { _error.value = it.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Every date of the trip (startDate..endDate). */
    val dates: StateFlow<List<String>> = event.map { e ->
        if (e == null || e.startDate.isEmpty()) listOf(initialDate)
        else {
            val s = LocalDate.parse(e.startDate)
            val end = LocalDate.parse(e.endDate.ifEmpty { e.startDate })
            generateSequence(s) { it.plusDays(1) }.takeWhile { it <= end }.map { it.toString() }.toList()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), listOf(initialDate))

    private val remoteStops: StateFlow<List<Stop>> = _date
        .flatMapLatest { trips.observeStops(groupId, eventId, it) }
        .catch { _error.value = it.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Local order override while dragging; null = follow Firestore. */
    private val draggedOrder = MutableStateFlow<List<String>?>(null)

    /** Live stops in route order (deleted ones excluded). */
    val stops: StateFlow<List<Stop>> = combine(remoteStops, draggedOrder) { remote, order ->
        val live = remote.filterNot { it.deleted }
        if (order == null) live
        else live.sortedBy { order.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The day document: status (READY/DRIVING/DONE) tells the timeline which node is "next". */
    val day: StateFlow<TripDay?> = _date
        .flatMapLatest { trips.observeDay(groupId, eventId, it) }
        .catch { _error.value = it.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val groups = GroupRepository()
    private val recordsRepo = RecordRepository()
    private val pickupsRepo = PickupRepository()

    /** 11장: who wants to be picked up today, in pickup order. */
    val pickups: StateFlow<List<Pickup>> = _date
        .flatMapLatest { pickupsRepo.observe(groupId, eventId, it) }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The trip's car (for the map dot while pickup sharing is on). */
    val tripCar: StateFlow<com.htb.carplan.shared.model.Car?> = event
        .flatMapLatest { e -> e?.carId?.let { calendar.observeCar(it) } ?: flowOf(null) }
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setMyPickup(place: Place) = write {
        val u = me(); val name = _names.value[u]?.ifBlank { null } ?: "나"
        pickupsRepo.setMine(groupId, eventId, _date.value, u, name, place)
    }
    fun removeMyPickup() = write { pickupsRepo.remove(groupId, eventId, _date.value, me()) }
    fun boarded() = write { pickupsRepo.setStatus(groupId, eventId, _date.value, me(), PickupStatus.ON_BOARD) }
    fun setPickupOrder(uids: List<String>) = write { pickupsRepo.setOrder(groupId, eventId, _date.value, uids) }

    /** 13장: what the car actually did today — records linked to stops and unplanned ones. */
    val records: StateFlow<List<Record>> = _date
        .flatMapLatest { recordsRepo.observeRecords(groupId, eventId, it) }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Record of each planned stop (newest wins when the car parked there twice). */
    val recordByStop: StateFlow<Map<String, Record>> = records
        .map { list -> list.filter { it.stopId != null }.associateBy { it.stopId!! } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 14장: a photo picked on the phone, downscaled by the caller to JPEG bytes (Q40: 10 per record). */
    fun addPhoto(recordId: String, jpeg: ByteArray) = write {
        val r = records.value.firstOrNull { it.id == recordId } ?: return@write
        if (r.photoUrls.size >= 10) { _error.value = "사진은 칸당 10장까지예요"; return@write }
        recordsRepo.addPhoto(groupId, eventId, _date.value, recordId, jpeg)
    }

    fun removePhoto(recordId: String, url: String) = write { recordsRepo.removePhoto(groupId, eventId, _date.value, recordId, url) }

    fun setNote(recordId: String, note: String) = write { recordsRepo.setNote(groupId, eventId, _date.value, recordId, note) }

    /** Q36: fix a wrong place name on an unplanned record. */
    fun setRecordPlace(recordId: String, place: Place) = write {
        val r = records.value.firstOrNull { it.id == recordId } ?: return@write
        val keepCoords = if (place.hasCoords()) place else place.copy(lat = r.place.lat, lng = r.place.lng)
        recordsRepo.setPlace(groupId, eventId, _date.value, recordId, keepCoords)
    }

    /** A record typed by hand (일상 기록 on a day the car did not drive, or a place the car missed). */
    fun addManualRecord(name: String) = write {
        recordsRepo.addRecord(groupId, eventId, _date.value, Record(place = Place(name = name.trim()), unplanned = true, createdBy = me()))
    }

    /** The group this trip belongs to (name in the header, 4장). */
    val group: StateFlow<Group?> = groups.observeGroup(groupId)
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** My car id (users/{uid}.carId) — enables "오늘 이 차로 가요" (7장 2). */
    val myCarId: StateFlow<String?> = uid.filterNotNull()
        .flatMapLatest { calendar.observeUser(it) }
        .map { it?.carId }
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 7장 2: this trip goes with my car, me driving. Null car = clear. */
    fun useMyCar(carId: String?) = write {
        groups.setTripCar(groupId, eventId, carId, if (carId == null) null else me())
    }

    /**
     * Q18: the driver's own phone is locked while the car drives this trip. For now the driver is the
     * car owner (7장 "오늘 이 차로 가요" will set Event.driverUid later).
     */
    val editLocked: StateFlow<Boolean> = uid.filterNotNull()
        .flatMapLatest { calendar.observeUser(it) }
        .flatMapLatest { u -> u?.carId?.let { calendar.observeCar(it) } ?: flowOf(null) }
        .map { car -> car != null && car.activeEventId == eventId && car.state.drivingRestricted }
        .combine(event) { lockedAsOwner, e -> lockedAsOwner && (e?.driverUid == null || e.driverUid == uid.value) }
        .catch { emit(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val remoteComments: StateFlow<List<Comment>> = _date
        .flatMapLatest { trips.observeDayComments(groupId, eventId, it) }
        .catch { _error.value = it.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Comments under each stop (3장 말풍선), oldest first, keyed by stop id. */
    val comments: StateFlow<Map<String, List<Comment>>> = remoteComments
        .map { list -> list.filter { it.stopId != null }.groupBy { it.stopId!! } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Stops someone removed; shown as "○○님이 △△를 뺐어요 · 되살리기". */
    val removed: StateFlow<List<Stop>> = remoteStops.map { all -> all.filter { it.deleted } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Drive between stop i and i+1, keyed by the first stop's id. */
    private val _legs = MutableStateFlow<Map<String, LegInfo>>(emptyMap())
    val legs: StateFlow<Map<String, LegInfo>> = _legs.asStateFlow()
    private val legCache = HashMap<String, LegInfo>()

    /** "예상 12:05" for stops without a typed time: previous time + stay + drive (Schedule). */
    val estimates: StateFlow<Map<String, String>> = combine(stops, _legs) { list, legs ->
        Schedule.estimate(list, { id -> legs[id]?.seconds })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** uid → display name for "○○님" labels (anonymous users have none → "일행"). */
    private val _names = MutableStateFlow<Map<String, String>>(emptyMap())
    val names: StateFlow<Map<String, String>> = _names.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { sessions.ensureSession() }
                .onSuccess { uid.value = it.uid }
                .onFailure { _error.value = it.message }
        }
        viewModelScope.launch { stops.collect { refreshLegs(it) } }
        viewModelScope.launch { remoteStops.collect { list -> list.mapNotNull { it.updatedBy }.distinct().forEach(::loadName) } }
        viewModelScope.launch { remoteComments.collect { list -> list.map { it.uid }.distinct().forEach(::loadName) } }
    }

    fun showDate(d: String) {
        draggedOrder.value = null
        _date.value = d
    }

    /** "내가" or "○○님이" for sentences like "… 를 뺐어요". */
    fun whoDid(uid: String?): String = if (uid != null && uid == this.uid.value) "내가" else "${nameOf(uid)}님이"

    fun nameOf(uid: String?): String = when {
        uid == null -> "일행"
        uid == this.uid.value -> "나"
        else -> _names.value[uid]?.takeIf { it.isNotBlank() } ?: "일행"
    }

    private fun loadName(uid: String) {
        if (_names.value.containsKey(uid)) return
        _names.value = _names.value + (uid to "")
        viewModelScope.launch {
            calendar.observeUser(uid).catch { }.collect { u -> _names.value = _names.value + (uid to u?.name.orEmpty()) }
        }
    }

    // ---------- editing ----------

    fun addStop(place: Place, time: String?, memo: String) = write {
        trips.addStop(groupId, eventId, _date.value, place, time, memo, me())
    }

    fun updateStop(stopId: String, place: Place, time: String?, memo: String) = write {
        trips.updateStop(groupId, eventId, _date.value, stopId, place, time, memo, me())
    }

    /** Tapping the time on the timeline: confirm an estimate or change the typed time in place. */
    fun setTime(stopId: String, time: String?) = write {
        val s = stops.value.firstOrNull { it.id == stopId } ?: return@write
        trips.updateStop(groupId, eventId, _date.value, stopId, s.place, time, s.memo, me())
    }

    fun addComment(stopId: String, text: String) = write {
        if (text.isBlank()) return@write
        trips.addComment(groupId, eventId, _date.value, stopId, text, me())
    }

    fun remove(stopId: String) = write { trips.setDeleted(groupId, eventId, _date.value, stopId, true, me()) }

    fun restore(stopId: String) = write { trips.setDeleted(groupId, eventId, _date.value, stopId, false, me()) }

    /** Called while dragging: moves [fromId] next to [toId] in the local order only. */
    fun moveLocally(fromId: String, toId: String) {
        val ids = stops.value.map { it.id }.toMutableList()
        val from = ids.indexOf(fromId)
        val to = ids.indexOf(toId)
        if (from < 0 || to < 0 || from == to) return
        ids.add(to, ids.removeAt(from))
        draggedOrder.value = ids
    }

    /** Called when the drag ends: writes the local order back. */
    fun commitOrder() {
        val order = draggedOrder.value ?: return
        write(keepLocalOrder = true) {
            trips.reorder(groupId, eventId, _date.value, order, me())
            draggedOrder.value = null
        }
    }

    /** [동선 최소로 정렬]: first stop stays, the rest by shortest total drive (only when the user asks). */
    fun sortShortest() {
        val list = stops.value
        if (list.size < 3) return
        write {
            // Fetch every pair once (suspend), then let the pure sorter read the table.
            val table = HashMap<Pair<String, String>, LegInfo>()
            for (a in list) for (b in list) if (a.id != b.id) legFor(a.place, b.place)?.let { table[a.id to b.id] = it }
            val cost: (Stop, Stop) -> Double? = { a, b -> table[a.id to b.id]?.let { l -> if (l.exact) l.seconds.toDouble() else l.meters.toDouble() } }
            val sorted = RouteMath.shortestOrder(list, cost)
            if (sorted.map { it.id } != list.map { it.id }) {
                trips.reorder(groupId, eventId, _date.value, sorted.map { it.id }, me())
            }
        }
    }

    // ---------- place search (Kakao, or typed name without a key) ----------

    val kakaoAvailable: Boolean get() = kakao.available

    suspend fun search(query: String): Result<List<Place>> =
        runCatching { kakao.searchKeyword(query, near = anchor()?.let { it.lat to it.lng }) }

    /** Places of [category] around [near] (phone location) or, failing that, the last stop with coordinates. */
    suspend fun recommend(category: KakaoCategory, near: Pair<Double, Double>?): Result<List<Place>> = runCatching {
        val (lat, lng) = near ?: anchor()?.let { it.lat to it.lng } ?: throw IllegalStateException("기준 위치가 없어요. 좌표가 있는 장소를 먼저 넣거나 위치 권한을 켜 주세요")
        kakao.searchCategory(category, lat, lng)
    }

    /** The last stop that has real coordinates (to bias searches and recommendations). */
    fun anchor(): Place? = stops.value.lastOrNull { it.place.hasCoords() }?.place

    // ---------- legs ----------

    private suspend fun refreshLegs(list: List<Stop>) {
        val out = LinkedHashMap<String, LegInfo>()
        list.zipWithNext().forEach { (a, b) -> legFor(a.place, b.place)?.let { out[a.id] = it } }
        _legs.value = out
    }

    /** Drive from [a] to [b]: Kakao Mobility when there is a key, otherwise a straight-line guess. */
    private suspend fun legFor(a: Place, b: Place): LegInfo? {
        val straight = RouteMath.meters(a, b) ?: return null
        val key = "${a.lat},${a.lng}>${b.lat},${b.lng}"
        legCache[key]?.let { return it }
        val leg = if (kakao.available) {
            runCatching { kakao.directions(a, b) }.map { LegInfo(it.seconds, it.meters, exact = true) }.getOrNull()
        } else null
        val result = leg ?: LegInfo(RouteMath.estimateSeconds(straight), straight.toInt(), exact = false)
        legCache[key] = result
        return result
    }

    private fun me() = uid.value ?: error("아직 로그인 중이에요")

    private fun write(keepLocalOrder: Boolean = false, block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            runCatching { block() }.onFailure {
                _error.value = if (it is KakaoKeyMissing) it.message else it.message
                if (keepLocalOrder) draggedOrder.value = null
            }
            _busy.value = false
        }
    }

    fun clearError() { _error.value = null }
}
