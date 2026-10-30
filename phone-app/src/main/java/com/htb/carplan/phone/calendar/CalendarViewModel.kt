package com.htb.carplan.phone.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.htb.carplan.shared.data.CalendarEvent
import com.htb.carplan.shared.data.CalendarRepository
import com.htb.carplan.shared.data.Session
import com.htb.carplan.shared.data.SessionRepository
import com.htb.carplan.shared.data.TripRepository
import com.htb.carplan.shared.model.BuiltInCategory
import com.htb.carplan.shared.model.Stop
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

    private val groupIds = _session.filterNotNull()
        .flatMapLatest { calendar.observeGroups(it.uid) }
        .map { groups -> groups.map { it.id } }

    /** Events overlapping the visible grid (it spills into neighbour months). */
    val events: StateFlow<List<CalendarEvent>> = combine(groupIds, _month) { ids, m -> ids to MonthGrid.of(m) }
        .flatMapLatest { (ids, grid) ->
            calendar.observeEvents(ids, grid.start.toString(), grid.endInclusive.toString())
        }
        .catch { _error.value = it.message }
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
    val tripStops: StateFlow<Map<String, List<Stop>>> = combine(events, _selected) { all, date ->
        MonthGrid.eventsOn(date, all).filter { it.event.categoryId == BuiltInCategory.TRIP.id }.map { it to date.toString() }
    }.flatMapLatest { trips ->
        if (trips.isEmpty()) flowOf(emptyMap())
        else combine(trips.map { (t, date) -> tripRepo.observeStops(t.groupId, t.event.id, date).map { t.event.id to it.filterNot { s -> s.deleted } } }) { it.toMap() }
    }
        .catch { _error.value = it.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

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
    fun addEvent(event: Event, onAdded: (groupId: String, eventId: String) -> Unit = { _, _ -> }) {
        val s = _session.value ?: return
        viewModelScope.launch {
            runCatching { calendar.addEvent(s.soloGroupId, event.copy(createdBy = s.uid)) }
                .onSuccess { onAdded(s.soloGroupId, it) }
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
