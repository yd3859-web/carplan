package com.htb.carplan.car

import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RouteStartInfo
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
import com.htb.carplan.shared.model.TripDay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalTime

/** Today's trip as the car sees it: the event, its day document and the stops in route order (deleted ones removed). */
data class TodayTrip(val groupId: String, val eventId: String, val title: String, val date: String, val stops: List<Stop>, val day: TripDay?) {
    val remaining: List<Stop> get() = stops.filterNot { it.visited }
    val next: Stop? get() = remaining.firstOrNull()
    val version: Long get() = day?.version ?: 0
}

enum class PromptReason { APP_START, GEAR_D, MANUAL, ROUTE_CHANGE }

/**
 * The question currently on screen. [reason] ROUTE_CHANGE (10장) carries [message] like
 * "정원님이 서현역을 뺐어요" and the buttons read 바꾸기 / 그대로 instead of 가기 / 나중에.
 */
data class NextPrompt(val stop: Stop, val reason: PromptReason, val listening: Boolean = false, val heard: String? = null, val message: String? = null)

/**
 * 9장 "다음 목적지로 갈까요?" + 10장 일정 변경 → 내비 갱신.
 * Watches the host's trip for today, asks (voice + buttons) when the app starts or the gear goes
 * P → D, feeds the navigation at most three places at a time (Q22) and refills as each is reached.
 * When members change the plan while a route is active, it waits 5 s (Q25), asks once (Q23), and
 * re-routes on "응"; on "아니" the phone shows "운전자가 보류함" and it asks again at the next stop (Q24).
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
    /** Every remaining stop at the moment the route was last sent (what the navigation "knows"). */
    private var routedPlan: List<Stop> = emptyList()
    private var routedVersion = -1L
    /** A change the driver has not accepted yet; asked again at the next P (Q24). */
    private var pendingChange = false
    /** The plan version the driver answered "그대로" to: not asked again until the plan changes or the car stops. */
    private var heldVersion = -1L
    private var askJob: Job? = null
    private var changeJob: Job? = null
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
                    // 7장 2: a trip that says "오늘 이 차로 가요" wins, then the one already active, then the first.
                    tripsToday.firstOrNull { it.event.carId == car.id }
                        ?: tripsToday.firstOrNull { it.event.id == car.activeEventId }
                        ?: tripsToday.firstOrNull()
                }
                .flatMapLatest { ev ->
                    if (ev == null) flowOf(null)
                    else combine(
                        trips.observeStops(ev.groupId, ev.event.id, today),
                        trips.observeDay(ev.groupId, ev.event.id, today),
                    ) { stops, day -> TodayTrip(ev.groupId, ev.event.id, ev.event.title, today, stops.filterNot { it.deleted }, day) }
                }
        }
        .catch { _error.value = it.message; emit(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        router.addListener(object : NaviHelperEventListener {
            override fun onWayPointArrived(info: WaypointArrivedInfo) { Log.i(TAG, "waypoint arrived ${info.name}"); onArrived() }
            override fun onDestinationArrived(info: DestinationArrivedInfo) { Log.i(TAG, "destination arrived ${info.name}"); onArrived() }
            override fun onRouteStarted(info: RouteStartInfo) { onRouteStarted(info.duration) }
            override fun onRouteCancelled() {
                // Our own re-request cancels the previous route first; only a cancel we did not cause ends guiding.
                if (System.currentTimeMillis() - lastRouteSentAt < OWN_CANCEL_WINDOW_MS) return
                Log.i(TAG, "route cancelled by navigation")
                routed = emptyList()
                _guiding.value = false
            }
        })
        scope.launch {
            trip.filterNotNull().collect { t ->
                // 9장 1: ask once when the app starts and today has stops left.
                if (!askedOnStart && t.next != null) { askedOnStart = true; ask(PromptReason.APP_START) }
                // 10장: the plan changed under an active route → wait 5 s for more edits, then compare.
                if (_guiding.value && routedVersion >= 0 && t.version != routedVersion && t.version != heldVersion) scheduleChangeCheck()
                // Drop a "다음 목적지" prompt whose stop vanished (a change prompt is handled above).
                _prompt.value?.let { p -> if (p.reason != PromptReason.ROUTE_CHANGE && t.stops.none { it.id == p.stop.id && !it.visited }) dismiss() }
            }
        }
    }

    fun bind(carId: String) { this.carId.value = carId }

    /** Gear events from [VehicleMonitor]: P → D asks "다음 목적지로 갈까요?"; D → P re-asks a held change (Q24). */
    fun onGear(gear: Gear) {
        val prev = lastGear
        lastGear = gear
        if (prev == Gear.P && gear == Gear.D && !_guiding.value && trip.value?.next != null) ask(PromptReason.GEAR_D)
        if (prev == Gear.D && gear == Gear.P && pendingChange) { heldVersion = -1; scheduleChangeCheck(delayMs = 0) }
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

    /** "응" / [가기] (9장) or [바꾸기] (10장): route the remaining stops and open the navigation. */
    fun go() {
        val t = trip.value ?: return
        askJob?.cancel()
        val wasChange = _prompt.value?.reason == PromptReason.ROUTE_CHANGE
        _prompt.value = null
        pendingChange = false
        heldVersion = -1
        sendRoute(t)
        scope.launch {
            runCatching {
                if (!wasChange) {
                    trips.setDayStatus(t.groupId, t.eventId, t.date, DayStatus.DRIVING)
                    carId.value?.let { cars.setActiveTrip(it, t.groupId, t.eventId) }
                }
                trips.setRouteSync(t.groupId, t.eventId, t.date, t.version, hold = false, etaNext = t.day?.etaNext)
            }.onFailure { _error.value = it.message }
        }
    }

    /** "아니" / [나중에] (9장): keep today's plan. [그대로] (10장): hold the change, phones show "운전자가 보류함". */
    fun later() {
        askJob?.cancel()
        val t = trip.value
        if (_prompt.value?.reason == PromptReason.ROUTE_CHANGE && t != null) {
            pendingChange = true
            heldVersion = t.version
            scope.launch {
                runCatching { trips.setRouteSync(t.groupId, t.eventId, t.date, routedVersion.coerceAtLeast(0), hold = true, etaNext = t.day?.etaNext) }
                    .onFailure { _error.value = it.message }
            }
        }
        _prompt.value = null
    }

    fun dismiss() = later()

    /** Emulator has no moving GPS: lets the demo mark the next stop as reached (20장 debug). */
    fun debugArrive() = onArrived()

    // ---------- 10장: plan changed while guiding ----------

    private fun scheduleChangeCheck(delayMs: Long = CHANGE_SETTLE_MS) {
        changeJob?.cancel()
        changeJob = scope.launch {
            delay(delayMs)
            val t = trip.value ?: return@launch
            if (!_guiding.value) return@launch
            val diff = describeChange(routedPlan, t.remaining)
            if (diff == null) {
                // Nothing the navigation cares about (e.g. a memo): just mark the version as reflected.
                routedVersion = t.version
                runCatching { trips.setRouteSync(t.groupId, t.eventId, t.date, t.version, hold = false, etaNext = t.day?.etaNext) }
                return@launch
            }
            val who = t.day?.lastChangedBy?.let { nameOf(it) } ?: "일행"
            askChange(t, "${who}님이 $diff")
        }
    }

    private fun askChange(t: TodayTrip, message: String) {
        val next = t.next ?: run {
            // Everything left was removed: stop guiding.
            router.route(emptyList()); routed = emptyList(); routedPlan = emptyList(); _guiding.value = false
            return
        }
        askJob?.cancel()
        _prompt.value = NextPrompt(next, PromptReason.ROUTE_CHANGE, message = message)
        askJob = scope.launch {
            val answer = voice.ask("$message. 경로를 바꿀까요?") { listening, heard ->
                _prompt.update { it?.copy(listening = listening, heard = heard ?: it.heard) }
            }
            when (answer) {
                VoicePrompt.Answer.YES -> go()
                VoicePrompt.Answer.NO -> later()
                VoicePrompt.Answer.UNKNOWN -> {
                    // Q24: no answer → the two buttons stay; the question returns at the next P.
                    pendingChange = true
                    heldVersion = t.version
                    _prompt.update { it?.copy(listening = false) }
                }
            }
        }
    }

    private suspend fun nameOf(uid: String): String =
        withTimeoutOrNull(3_000) { calendar.observeUser(uid).first()?.name?.takeIf { it.isNotBlank() } } ?: "일행"

    private fun sendRoute(t: TodayTrip) {
        val batch = t.remaining.take(MAX_PLACES)
        routedPlan = t.remaining
        routedVersion = t.version
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
        Log.i(TAG, "routed ${routed.size} of ${t.remaining.size} remaining (version ${t.version})")
    }

    /** The navigation computed a route: publish when it expects to reach the next stop (10장 7·8). */
    private fun onRouteStarted(durationSeconds: Int) {
        val t = trip.value ?: return
        val eta = LocalTime.now().plusSeconds(durationSeconds.toLong()).let { "%02d:%02d".format(it.hour, it.minute) }
        Log.i(TAG, "route started, next eta $eta")
        scope.launch {
            runCatching { trips.setRouteSync(t.groupId, t.eventId, t.date, routedVersion.coerceAtLeast(0), hold = false, etaNext = eta) }
        }
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
                    routedPlan = emptyList()
                    _guiding.value = false
                    voice.say("오늘 일정을 모두 다녀왔어요.")
                } else if (after.size > routed.size) {
                    // More stops than the navigation holds: refill from the car's position.
                    sendRoute(t.copy(stops = t.stops.map { if (it.id == reached.id) it.copy(visited = true) else it }))
                } else {
                    routedPlan = after
                }
            }.onFailure { _error.value = it.message }
        }
    }

    companion object {
        private const val TAG = "TripNavigator"
        /** 2 waypoints + destination (SDK names only FIRST and SECOND waypoint). */
        const val MAX_PLACES = 3
        private const val OWN_CANCEL_WINDOW_MS = 5_000L
        /** Q25: several quick edits become one question. */
        private const val CHANGE_SETTLE_MS = 5_000L

        /** "해운대로" / "광안리로" / "감천문화마을로" — picks 로/으로 by the last syllable's final consonant. */
        fun withRo(name: String): String {
            val last = name.lastOrNull() ?: return name
            if (last !in '가'..'힣') return "$name(으)로"
            val jong = (last - '가') % 28
            return name + if (jong == 0 || jong == 8) "로" else "으로"
        }

        /** "서현역을 뺐어요" / "정자역을 넣었어요" / "순서를 바꿨어요" / "A를 B로 바꿨어요"; null when the route is unaffected. */
        fun describeChange(before: List<Stop>, after: List<Stop>): String? {
            val beforeIds = before.map { it.id }
            val afterIds = after.map { it.id }
            val removed = before.filter { it.id !in afterIds }
            val added = after.filter { it.id !in beforeIds }
            val moved = removed.isEmpty() && added.isEmpty() && beforeIds != afterIds
            val replaced = after.filter { a -> before.firstOrNull { it.id == a.id }?.let { b -> b.place.name != a.place.name || b.place.lat != a.place.lat || b.place.lng != a.place.lng } == true }
            return when {
                removed.isNotEmpty() && added.isNotEmpty() -> "${removed.first().place.name} 대신 ${added.first().place.name}${eul(added.first().place.name)} 넣었어요"
                removed.isNotEmpty() -> "${removed.first().place.name}${eul(removed.first().place.name)} 뺐어요"
                added.isNotEmpty() -> "${added.first().place.name}${eul(added.first().place.name)} 넣었어요"
                replaced.isNotEmpty() -> {
                    val b = before.first { it.id == replaced.first().id }
                    "${b.place.name}${eul(b.place.name)} ${replaced.first().place.name}${ro(replaced.first().place.name)} 바꿨어요"
                }
                moved -> "순서를 바꿨어요"
                else -> null
            }
        }

        private fun eul(name: String): String {
            val last = name.lastOrNull() ?: return "를"
            if (last !in '가'..'힣') return "을(를)"
            return if ((last - '가') % 28 == 0) "를" else "을"
        }

        private fun ro(name: String): String = withRo(name).removePrefix(name)
    }
}
