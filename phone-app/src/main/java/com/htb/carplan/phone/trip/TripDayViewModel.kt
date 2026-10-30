package com.htb.carplan.phone.trip

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.htb.carplan.phone.BuildConfig
import com.htb.carplan.shared.calendar.RouteMath
import com.htb.carplan.shared.calendar.RouteMath.hasCoords
import com.htb.carplan.shared.data.CalendarRepository
import com.htb.carplan.shared.data.SessionRepository
import com.htb.carplan.shared.data.TripRepository
import com.htb.carplan.shared.model.Event
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

    /** Stops someone removed; shown as "○○님이 △△를 뺐어요 · 되살리기". */
    val removed: StateFlow<List<Stop>> = remoteStops.map { all -> all.filter { it.deleted } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Drive between stop i and i+1, keyed by the first stop's id. */
    private val _legs = MutableStateFlow<Map<String, LegInfo>>(emptyMap())
    val legs: StateFlow<Map<String, LegInfo>> = _legs.asStateFlow()
    private val legCache = HashMap<String, LegInfo>()

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
