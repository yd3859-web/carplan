package com.htb.carplan.car

import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.util.Log
import com.htb.carplan.shared.calendar.RouteMath.hasCoords
import com.htb.carplan.shared.data.CalendarRepository
import com.htb.carplan.shared.data.CarRepository
import com.htb.carplan.shared.data.TripRepository
import com.htb.carplan.shared.model.BuiltInCategory
import com.htb.carplan.shared.model.DayStatus
import com.htb.carplan.shared.model.Gear
import com.htb.carplan.shared.model.Stop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Today's trip as the car sees it: the event plus its stops in route order (deleted ones removed). */
data class TodayTrip(val groupId: String, val eventId: String, val title: String, val date: String, val stops: List<Stop>) {
    val remaining: List<Stop> get() = stops.filterNot { it.visited }
    val next: Stop? get() = remaining.firstOrNull()
}

enum class PromptReason { APP_START, GEAR_D, MANUAL }

/** The "다음 목적지로 갈까요?" question currently on screen. */
data class NextPrompt(val stop: Stop, val reason: PromptReason, val listening: Boolean = false, val heard: String? = null)

/**
 * 9장 "다음 목적지로 갈까요?". Watches the host's trip for today, asks (voice + buttons)
 * when the app starts or the gear goes P → D, and feeds the navigation at most three
 * places at a time (2 waypoints + destination, Q22), refilling as each one is reached.
 * Lives in [CarRuntime] so the foreground service keeps it alive behind the navigation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripNavigator(
    private val router: NaviRouter,
    private val voice: VoicePrompt,
    private val scope: CoroutineScope,
) {
    private val calendar = CalendarRepository()
    private val trips = TripRepository()
    private val cars = CarRepository()

    private val carId = MutableStateFlow<String?>(null)
    private val _prompt = MutableStateFlow<NextPrompt?>(null)
    val prompt: StateFlow<NextPrompt?> = _prompt.asStateFlow()
    private val _guiding = MutableStateFlow(false)
    /** True once a route for today's trip has been sent to the navigation. */
    val guiding: StateFlow<Boolean> = _guiding.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Stop ids that are currently inside the navigation route, in order. */
    private var routed: List<String> = emptyList()
    private var askJob: Job? = null
    private var askedOnStart = false
    private var lastGear = Gear.UNKNOWN
    private var lastRouteSentAt = 0L

    /** Today's trip of the car owner: prefers the one the car already marked active, else the first trip of the day. */
    val trip: StateFlow<TodayTrip?> = carId.filterNotNull()
        .flatMapLatest { id -> calendar.observeCar(id) }
        .flatMapLatest { car ->
            val owner = car?.ownerUid ?: return@flatMapLatest flowOf(null)
            val today = LocalDate.now().toString()
            calendar.observeGroups(owner)
                .flatMapLatest { groups -> calendar.observeEvents(groups.map { it.id }, today, today) }
                .map { events ->
                    val tripsToday = events.filter { it.event.categoryId == BuiltInCategory.TRIP.id && !it.event.deleted }
                    tripsToday.firstOrNull { it.event.id == car.activeEventId } ?: tripsToday.firstOrNull()
                }
                .flatMapLatest { ev ->
                    if (ev == null) flowOf(null)
                    else trips.observeStops(ev.groupId, ev.event.id, today)
                        .map { stops -> TodayTrip(ev.groupId, ev.event.id, ev.event.title, today, stops.filterNot { it.deleted }) }
                }
        }
        .catch { _error.value = it.message; emit(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        router.addListener(object : NaviHelperEventListener {
            override fun onWayPointArrived(info: WaypointArrivedInfo) { Log.i(TAG, "waypoint arrived ${info.name}"); onArrived() }
            override fun onDestinationArrived(info: DestinationArrivedInfo) { Log.i(TAG, "destination arrived ${info.name}"); onArrived() }
            override fun onRouteCancelled() {
                // Our own re-request cancels the previous route first; only a cancel we did not cause ends guiding.
                if (System.currentTimeMillis() - lastRouteSentAt < OWN_CANCEL_WINDOW_MS) return
                Log.i(TAG, "route cancelled by navigation")
                routed = emptyList()
                _guiding.value = false
            }
        })
        // Ask once when the app starts and today has a trip with stops left (9장 1).
        scope.launch {
            trip.filterNotNull().collect { t ->
                if (!askedOnStart && t.next != null) { askedOnStart = true; ask(PromptReason.APP_START) }
                // The itinerary can change under us (10장 handles the question; here just drop stale prompts).
                _prompt.value?.let { p -> if (t.stops.none { it.id == p.stop.id && !it.visited }) dismiss() }
            }
        }
    }

    fun bind(carId: String) { this.carId.value = carId }

    /** Gear events from [VehicleMonitor]: P → D with stops left and no route yet → ask (9장 1). */
    fun onGear(gear: Gear) {
        val prev = lastGear
        lastGear = gear
        if (prev == Gear.P && gear == Gear.D && !_guiding.value && trip.value?.next != null) ask(PromptReason.GEAR_D)
    }

    /** The 🎤 button: ask again now. */
    fun askNow() = ask(PromptReason.MANUAL)

    private fun ask(reason: PromptReason) {
        val next = trip.value?.next ?: return
        askJob?.cancel()
        _prompt.value = NextPrompt(next, reason)
        askJob = scope.launch {
            val answer = voice.ask("다음 목적지 ${withRo(next.place.name)} 갈까요?") { listening, heard ->
                _prompt.update { it?.copy(listening = listening, heard = heard ?: it.heard) }
            }
            when (answer) {
                VoicePrompt.Answer.YES -> go()
                VoicePrompt.Answer.NO -> later()
                VoicePrompt.Answer.UNKNOWN -> _prompt.update { it?.copy(listening = false) } // buttons stay (Q21)
            }
        }
    }

    /** "응" / [가기]: route the remaining stops and open the navigation. */
    fun go() {
        val t = trip.value ?: return
        askJob?.cancel()
        _prompt.value = null
        sendRoute(t)
        scope.launch {
            runCatching {
                trips.setDayStatus(t.groupId, t.eventId, t.date, DayStatus.DRIVING)
                carId.value?.let { cars.setActiveTrip(it, t.groupId, t.eventId) }
            }.onFailure { _error.value = it.message }
        }
    }

    /** "아니" / [나중에]: keep today's plan, ask again at the next P → D. */
    fun later() {
        askJob?.cancel()
        _prompt.value = null
    }

    fun dismiss() = later()

    /** Emulator has no moving GPS: lets the demo mark the next stop as reached (20장 debug). */
    fun debugArrive() = onArrived()

    private fun sendRoute(t: TodayTrip) {
        val batch = t.remaining.take(MAX_PLACES)
        if (batch.isEmpty()) {
            routed = emptyList()
            _guiding.value = false
            return
        }
        val withCoords = batch.filter { it.place.hasCoords() }
        if (withCoords.isEmpty()) {
            // Name-only stops (no Kakao key yet): hand the name to the navigation's own search.
            router.search(batch.first().place.name)
            routed = listOf(batch.first().id)
        } else {
            router.route(withCoords.map { it.place })
            routed = withCoords.map { it.id }
            router.openNavigation()
        }
        lastRouteSentAt = System.currentTimeMillis()
        _guiding.value = true
        Log.i(TAG, "routed ${routed.size} of ${t.remaining.size} remaining")
    }

    /** One place reached: mark it visited, then top the route up while more stops remain (Q22). */
    private fun onArrived() {
        val t = trip.value ?: return
        val reached = routed.firstOrNull()?.let { id -> t.stops.firstOrNull { it.id == id } } ?: t.next ?: return
        routed = routed.drop(1)
        scope.launch {
            val after = t.remaining.filterNot { it.id == reached.id }
            runCatching {
                trips.setVisited(t.groupId, t.eventId, t.date, reached.id, true, t.stops.indexOfFirst { it.id == after.firstOrNull()?.id }.coerceAtLeast(0))
                if (after.isEmpty()) {
                    trips.setDayStatus(t.groupId, t.eventId, t.date, DayStatus.DONE)
                    routed = emptyList()
                    _guiding.value = false
                    voice.say("오늘 일정을 모두 다녀왔어요.")
                } else if (after.size > routed.size) {
                    // More stops than the navigation holds: refill from the car's position.
                    sendRoute(t.copy(stops = t.stops.map { if (it.id == reached.id) it.copy(visited = true) else it }))
                }
            }.onFailure { _error.value = it.message }
        }
    }

    companion object {
        private const val TAG = "TripNavigator"
        /** 2 waypoints + destination (SDK names only FIRST and SECOND waypoint). */
        const val MAX_PLACES = 3
        private const val OWN_CANCEL_WINDOW_MS = 5_000L

        /** "해운대로" / "광안리로" / "감천문화마을로" — picks 로/으로 by the last syllable's final consonant. */
        fun withRo(name: String): String {
            val last = name.lastOrNull() ?: return name
            if (last !in '가'..'힣') return "$name(으)로"
            val jong = (last - '가') % 28
            return name + if (jong == 0 || jong == 8) "로" else "으로"
        }
    }
}
