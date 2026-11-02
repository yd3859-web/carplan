package com.htb.carplan.phone.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.htb.carplan.shared.data.CalendarEvent
import com.htb.carplan.shared.data.CalendarRepository
import com.htb.carplan.shared.data.FirestorePaths
import com.htb.carplan.shared.data.RecordRepository
import com.htb.carplan.shared.calendar.CategoryColors
import com.htb.carplan.shared.calendar.Commute
import com.htb.carplan.shared.calendar.Repeat
import kotlinx.coroutines.flow.first
import com.htb.carplan.shared.data.GroupRepository
import com.htb.carplan.shared.model.Group
import com.htb.carplan.shared.data.Session
import com.htb.carplan.shared.data.SessionRepository
import com.htb.carplan.shared.data.TripRepository
import com.htb.carplan.shared.model.BuiltInCategory
import com.htb.carplan.shared.model.Stop
import com.htb.carplan.shared.model.Comment
import com.htb.carplan.shared.model.TripDay
import com.htb.carplan.shared.model.Car
import com.htb.carplan.shared.model.CustomCategory
import com.htb.carplan.shared.model.Event
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel : ViewModel() {
    private val sessions = SessionRepository()
    private val calendar = CalendarRepository()
    private val tripRepo = TripRepository()

    private val _session = MutableStateFlow<Session?>(null)
    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month.asStateFlow()
    private val _selected = MutableStateFlow(LocalDate.now())
    val selected: StateFlow<LocalDate> = _selected.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Every group I belong to (solo last) — the 여행 추가 창 picks one (4장). */
    val groups: StateFlow<List<Group>> = _session.filterNotNull()
        .flatMapLatest { calendar.observeGroups(it.uid) }
        .map { list -> list.sortedWith(compareBy({ it.solo }, { it.name })) }
        .catch { _error.value = it.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val groupIds = groups.map { list -> list.map { it.id } }

    /** The group the last trip went to; a new trip defaults to it (falls back to the solo group). */
    private val _lastGroupId = MutableStateFlow<String?>(null)
    val lastGroupId: StateFlow<String?> = _lastGroupId.asStateFlow()

    /** Events overlapping the visible grid (it spills into neighbour months), plus Google Calendar's (Q05, read-only). */
    val events: StateFlow<List<CalendarEvent>> = combine(groupIds, _month) { ids, m -> ids to MonthGrid.of(m) }
        .flatMapLatest { (ids, grid) ->
            calendar.observeEvents(ids, grid.start.toString(), grid.endInclusive.toString())
        }
        .catch { _error.value = it.message }
        .combine(GoogleCalendar.events) { ours, google -> ours + google }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The selected day's events, on their own: browsing to another month (swipe, arrows) must not empty 그날 동선 or
     * the today card's stops while the selected day stays where it was.
     */
    val selectedEvents: StateFlow<List<CalendarEvent>> = combine(groupIds, _selected) { ids, d -> ids to d }
        .flatMapLatest { (ids, d) -> calendar.observeEvents(ids, d.toString(), d.toString()).map { MonthGrid.eventsOn(d, it) } }
        .catch { _error.value = it.message }
        .combine(GoogleCalendar.events) { ours, google -> ours + MonthGrid.eventsOn(_selected.value, google) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Today and the next 60 days, independent of the month being browsed (오늘 카드). */
    val upcoming: StateFlow<List<CalendarEvent>> = groupIds
        .flatMapLatest { ids ->
            val today = LocalDate.now()
            calendar.observeEvents(ids, today.toString(), today.plusDays(60).toString())
        }
        .catch { _error.value = it.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The linked car (7장); null until the car app is linked. */
    val car: StateFlow<Car?> = _session.filterNotNull()
        .flatMapLatest { calendar.observeUser(it.uid) }
        .flatMapLatest { user -> user?.carId?.let(calendar::observeCar) ?: flowOf(null) }
        .catch { _error.value = it.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Stops of every trip on the selected day, keyed by event id (shown in 그날 동선). */
    val tripStops: StateFlow<Map<String, List<Stop>>> = combine(selectedEvents, _selected) { day, date ->
        day.filter { it.event.categoryId == BuiltInCategory.TRIP.id }.map { it to date.toString() }
    }.flatMapLatest { trips ->
        if (trips.isEmpty()) flowOf(emptyMap())
        else combine(trips.map { (t, date) -> tripRepo.observeStops(t.groupId, t.event.id, date).map { t.event.id to it.filterNot { s -> s.deleted } } }) { it.toMap() }
    }
        .catch { _error.value = it.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Today's trip stops for the today card, whatever day is selected below. */
    val todayStops: StateFlow<Map<String, List<Stop>>> = upcoming.map { all ->
        val today = LocalDate.now()
        MonthGrid.eventsOn(today, all).filter { it.event.categoryId == BuiltInCategory.TRIP.id }.map { it to today.toString() }
    }.distinctUntilChanged().flatMapLatest { trips ->
        if (trips.isEmpty()) flowOf(emptyMap())
        else combine(trips.map { (t, date) -> tripRepo.observeStops(t.groupId, t.event.id, date).map { t.event.id to it.filterNot { s -> s.deleted } } }) { it.toMap() }
    }
        .catch { _error.value = it.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val selectedTrips = combine(selectedEvents, _selected) { day, date ->
        day.filter { it.event.categoryId == BuiltInCategory.TRIP.id }.map { it to date.toString() }
    }

    /** Latest comments under each trip's stops (그날 동선 shows the newest one per stop). */
    val tripComments: StateFlow<Map<String, List<Comment>>> = selectedTrips.flatMapLatest { trips ->
        if (trips.isEmpty()) flowOf(emptyMap())
        else combine(trips.map { (t, date) -> tripRepo.observeDayComments(t.groupId, t.event.id, date).map { t.event.id to it } }) { it.toMap() }
    }
        .catch { _error.value = it.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Day status per trip (DRIVING makes the first unreached stop the "next" node). */
    val tripDays: StateFlow<Map<String, TripDay?>> = selectedTrips.flatMapLatest { trips ->
        if (trips.isEmpty()) flowOf(emptyMap())
        else combine(trips.map { (t, date) -> tripRepo.observeDay(t.groupId, t.event.id, date).map { t.event.id to it } }) { it.toMap() }
    }
        .catch { _error.value = it.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 16장 Q45: a commute the last 30 days of records suggest; null = nothing (or dismissed today). */
    private val _commuteSuggestion = MutableStateFlow<Commute.Suggestion?>(null)
    val commuteSuggestion: StateFlow<Commute.Suggestion?> = _commuteSuggestion.asStateFlow()
    private var suggestionDismissed = false

    private suspend fun learnCommute(s: Session, existing: List<CalendarEvent>) {
        if (suggestionDismissed) return
        val records = RecordRepository()
        val today = LocalDate.now()
        val pairs = mutableListOf<Pair<LocalDate, com.htb.carplan.shared.model.Record>>()
        for (i in 0 until 30) {
            val d = today.minusDays(i.toLong())
            val list = runCatching { records.observeRecords(s.soloGroupId, FirestorePaths.dailyLogEventId(d.toString()), d.toString()).first() }.getOrNull().orEmpty()
            list.forEach { pairs += d to it }
        }
        _commuteSuggestion.value = Commute.suggest(pairs, existing.map { it.event })
    }

    /** "등록": a repeating 출퇴근 event (category made on first use) at the suggested time. */
    fun registerCommute(sg: Commute.Suggestion, title: String = "출근") {
        val s = _session.value ?: return
        viewModelScope.launch {
            runCatching {
                val cat = customCategories.value.firstOrNull { it.name == "출퇴근" }?.id
                    ?: calendar.addCustomCategory(s.uid, "출퇴근", CategoryColors.PALETTE[3])
                val today = LocalDate.now().toString()
                calendar.addEvent(s.soloGroupId, Event(categoryId = cat, title = title, startDate = today, endDate = today, allDay = false, startTime = sg.time, place = sg.place, repeat = Repeat.weeklyRule(sg.days), createdBy = s.uid))
                _commuteSuggestion.value = null
            }.onFailure { _error.value = it.message }
        }
    }

    fun dismissCommute() { suggestionDismissed = true; _commuteSuggestion.value = null }

    val customCategories: StateFlow<List<CustomCategory>> = _session
        .flatMapLatest { s -> if (s == null) emptyFlow() else calendar.observeCustomCategories(s.uid) }
        .catch { _error.value = it.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            runCatching { sessions.ensureSession() }
                .onSuccess { _session.value = it }
                .onFailure { _error.value = it.message }
        }
        viewModelScope.launch {
            val s = _session.filterNotNull().first()
            val existing = upcoming.first { true }
            runCatching { learnCommute(s, existing) }
        }
    }

    fun showMonth(m: YearMonth) { _month.value = m }

    fun select(date: LocalDate) {
        _selected.value = date
        if (YearMonth.from(date) != _month.value) _month.value = YearMonth.from(date)
    }

    /**
     * Every event goes to the solo group for now (4장 adds picking a group for trips).
     * [onAdded] gets the group and event id so a trip can open its first day right away.
     */
    fun addEvent(event: Event, groupId: String? = null, firstStop: com.htb.carplan.shared.model.Place? = null, onAdded: (groupId: String, eventId: String) -> Unit = { _, _ -> }) {
        val s = _session.value ?: return
        val gid = groupId ?: s.soloGroupId
        if (event.categoryId == BuiltInCategory.TRIP.id) _lastGroupId.value = gid
        // 초대 = 일정: "with 가족" makes the event its own group with 가족's people; it never lives in the 묶음.
        val circle = groups.value.firstOrNull { it.id == gid && it.isCircle }
        viewModelScope.launch {
            runCatching {
                if (circle != null) GroupRepository().addEventWith(circle, event.copy(createdBy = s.uid), s.uid)
                else gid to calendar.addEvent(gid, event.copy(createdBy = s.uid))
            }
                .onSuccess { (g, eid) ->
                    // A안: the place picked with [다트] is the first day's first stop.
                    firstStop?.let { p -> runCatching { com.htb.carplan.shared.data.TripRepository().addStop(g, eid, event.startDate, p, null, "다트", s.uid) } }
                    onAdded(g, eid)
                }
                .onFailure { _error.value = it.message }
        }
    }

    fun addCategory(name: String, color: Long, onAdded: (String) -> Unit) {
        val s = _session.value ?: return
        viewModelScope.launch {
            runCatching { calendar.addCustomCategory(s.uid, name, color) }
                .onSuccess(onAdded)
                .onFailure { _error.value = it.message }
        }
    }
}
