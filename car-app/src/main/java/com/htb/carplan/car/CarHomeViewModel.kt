package com.htb.carplan.car

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.htb.carplan.shared.data.CalendarEvent
import com.htb.carplan.shared.data.CalendarRepository
import com.htb.carplan.shared.data.CarRepository
import com.htb.carplan.shared.data.ensureSignedIn
import com.htb.carplan.shared.model.Car
import com.htb.carplan.shared.model.CarState
import com.htb.carplan.shared.model.Event
import com.htb.carplan.shared.model.User
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * 8장 차 첫 화면. The car shows its owner's (host's) calendar: every group the
 * owner belongs to (Q17), this week only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CarHomeViewModel(app: Application) : AndroidViewModel(app) {
    private val calendar = CalendarRepository()
    private val cars = CarRepository()
    private val router = CarRuntime.router(app)

    private val carId = MutableStateFlow<String?>(null)
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val vehicle: StateFlow<CarState> = CarRuntime.monitor(app).state

    val car: StateFlow<Car?> = carId.filterNotNull()
        .flatMapLatest { calendar.observeCar(it) }
        .catch { _error.value = it.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val owner: StateFlow<User?> = car
        .flatMapLatest { c -> c?.ownerUid?.let(calendar::observeUser) ?: flowOf(null) }
        .catch { _error.value = it.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val weekStart: LocalDate = LocalDate.now().let { it.minusDays((it.dayOfWeek.value % 7).toLong()) }

    /** Owner's events from this Sunday through next Saturday. */
    val week: StateFlow<List<CalendarEvent>> = owner
        .flatMapLatest { u -> if (u == null) flowOf(emptyList()) else calendar.observeGroups(u.uid) }
        .map { groups -> groups.map { it.id } }
        .flatMapLatest { ids -> calendar.observeEvents(ids, weekStart.toString(), weekStart.plusDays(6).toString()) }
        .catch { _error.value = it.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        TripSyncService.start(app)
        viewModelScope.launch {
            runCatching {
                val uid = FirebaseAuth.getInstance().ensureSignedIn()
                cars.ensureCar(uid)
                uid
            }.onSuccess { carId.value = it }.onFailure { _error.value = it.message }
        }
    }

    fun eventsOn(date: LocalDate, all: List<CalendarEvent>): List<CalendarEvent> = all.filter {
        date.toString() in it.event.startDate..it.event.endDate.ifEmpty { it.event.startDate }
    }.sortedWith(compareBy({ !it.event.allDay }, { it.event.startTime ?: "" }))

    /** Today's next event that has a place and is not past. */
    fun nextDestination(all: List<CalendarEvent>): CalendarEvent? {
        val now = LocalTime.now().toString().take(5)
        return eventsOn(LocalDate.now(), all)
            .filter { it.event.place != null && (it.event.startTime == null || it.event.startTime!! >= now) }
            .minByOrNull { it.event.startTime ?: "99" }
    }

    fun goTo(item: CalendarEvent) {
        val place = item.event.place ?: return
        // Name-only places (before Kakao search) go to the navigation's own search.
        if (place.lat == 0.0 && place.lng == 0.0) {
            router.search(place.name)
        } else {
            router.route(listOf(place))
            router.openNavigation()
        }
    }

    fun openNavigation() = router.openNavigation()

    /** 차에서 일정 만들기 (Q19), parked only. Goes to the owner's solo group. */
    fun addEvent(event: Event) {
        val u = owner.value ?: return
        viewModelScope.launch {
            runCatching { calendar.addEvent(u.soloGroupId, event.copy(createdBy = u.uid)) }
                .onFailure { _error.value = it.message }
        }
    }
}
