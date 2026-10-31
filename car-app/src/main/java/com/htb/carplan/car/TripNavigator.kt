package com.htb.carplan.car

import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.util.Log
import com.htb.carplan.shared.calendar.FuelPlan
import com.htb.carplan.shared.calendar.RouteMath.hasCoords
import com.htb.carplan.shared.data.CalendarRepository
import com.htb.carplan.shared.data.CarRepository
import com.htb.carplan.shared.data.GroupRepository
import com.htb.carplan.shared.data.MaintenanceRepository
import com.htb.carplan.shared.data.PickupRepository
import com.htb.carplan.shared.model.Pickup
import com.htb.carplan.shared.model.PickupStatus
import com.htb.carplan.shared.model.MaintenanceItem
import com.htb.carplan.shared.data.RecordRepository
import com.htb.carplan.shared.calendar.RouteMath
import com.htb.carplan.shared.model.CarState
import com.htb.carplan.shared.model.Event
import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.model.Record
import com.htb.carplan.shared.net.GasStation
import com.htb.carplan.shared.net.KakaoApi
import com.htb.carplan.shared.net.OpinetApi
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
data class TodayTrip(val groupId: String, val eventId: String, val title: String, val date: String, val stops: List<Stop>, val day: TripDay?, val pickups: List<Pickup> = emptyList()) {
    val remaining: List<Stop> get() = stops.filterNot { it.visited }
    /** People still to collect, in pickup order (11장): they go in front of the stops. */
    val waitingPickups: List<Pickup> get() = pickups.filter { it.status == PickupStatus.WAITING && it.place.hasCoords() }
    val next: Stop? get() = remaining.firstOrNull()
    val version: Long get() = day?.version ?: 0
}

/** One place the navigation is driving to right now, in order (묶음 2: 칸·픽업·주유소가 한 목록). */
/** [말하기] on screen: the mic is open, or the known commands are shown as buttons (nothing understood). */
data class VoiceState(val listening: Boolean = false, val choices: Boolean = false, val heard: String? = null)

data class NaviEntry(val kind: Kind, val id: String, val place: Place) { enum class Kind { STOP, PICKUP, STATION } }

enum class PromptReason { APP_START, GEAR_D, MANUAL, ROUTE_CHANGE, FUEL, FUELED, PICKUP, COMMUTE, PARKING }

/** What the navigation tells us while guiding — the 주행 화면 draws this (the SDK has no map view to embed). */
data class NaviLive(
    val turn: TurnType? = null,
    val turnMeters: Double? = null,
    val turnText: String? = null,
    val remainMeters: Int? = null,
    val remainSeconds: Int? = null,
    val nextName: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val heading: Double? = null,
    val address: String? = null,
)

/**
 * The question currently on screen. [reason] ROUTE_CHANGE (10장) carries [message] like
 * "정원님이 서현역을 뺐어요" and the buttons read 바꾸기 / 그대로 instead of 가기 / 나중에.
 */
data class NextPrompt(
    val stop: Stop, val reason: PromptReason, val listening: Boolean = false, val heard: String? = null, val message: String? = null,
    /** 차 화면 원칙 (정원 2026-10-31): not a question — spoken once, one big button ([button]) does the finding, nothing to answer. */
    val single: Boolean = false, val button: String? = null,
)

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
    private val records = RecordRepository()
    private val groups = GroupRepository()
    private val opinet = OpinetApi(BuildConfig.OPINET_KEY)
    private val maintenance = MaintenanceRepository()
    private val pickupsRepo = PickupRepository()
    private val kakao = KakaoApi(BuildConfig.KAKAO_REST_KEY)
    /** Driving seconds between two rounded coordinates, so ordering and ETA don't re-ask Kakao for the same leg. */
    private val legCache = HashMap<String, Int>()
    private var etaJob: Job? = null
    private var shareJob: Job? = null
    private var commuteEvent: Event? = null
    private var commuteDeclinedDate: String? = null
    private var commuteAskedDate: String? = null
    private var fuelAtPark: Double? = null
    /** The station the driver picked (12장), so the fill-up record says where and at what price. */
    private var pickedStation: GasStation? = null
    private var fueledAsked = false
    /** 12장 설정: cars/{id}.fuelAlertPercent (default 20); the range threshold scales with it (20% → 50 km). */
    private var fuelAlertPercent = LOW_FUEL_PERCENT

    private val carId = MutableStateFlow<String?>(null)
    private val _prompt = MutableStateFlow<NextPrompt?>(null)
    /** 묶음 7: one thing on screen at a time. A lower-priority prompt waits here until the current one is answered. */
    private val waiting = ArrayDeque<Pair<NextPrompt, () -> Unit>>()
    val prompt: StateFlow<NextPrompt?> = _prompt.asStateFlow()
    private val _guiding = MutableStateFlow(false)
    /** True once a route for today's trip has been sent to the navigation. */
    val guiding: StateFlow<Boolean> = _guiding.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _live = MutableStateFlow(NaviLive())
    /** 주행 화면: next turn, remaining distance/time, position (refreshed every few seconds while guiding). */
    val live: StateFlow<NaviLive> = _live.asStateFlow()
    private var liveJob: Job? = null

    /**
     * What the navigation holds right now, in driving order — pickups, stops and a petrol station all in one list.
     * Arrival, refill, the 10장 comparison and [원래대로] all read this one (2026-10-31, 묶음 2; fixes N1 where a
     * station arrival ticked off the next stop).
     */
    private var inNavi: List<NaviEntry> = emptyList()
    /** Every remaining stop at the moment the route was last sent (what the navigation "knows"). */
    private var routedPlan: List<Stop> = emptyList()
    private var routedVersion = -1L
    /** A change the driver has not accepted yet; asked again at the next P (Q24). */
    /** 10장 (정원 2026-10-31): changes go to the navigation by themselves; [원래대로] puts this route back for a while. */
    private var previousRoute: List<Place> = emptyList()
    private var previousVersion = -1L
    /** The plan version the driver reverted: the navigation keeps the old route until the plan changes again. */
    private var revertedVersion = -1L
    private var revertJob: Job? = null
    private var askJob: Job? = null
    private var changeJob: Job? = null
    private var askedOnStart = false
    private var lastGear = Gear.UNKNOWN
    private var lastRouteSentAt = 0L

    // ---- 13장 arrival → record
    private var parkTimer: Job? = null
    /** Set once a record was made for this parking; cleared when the car drives off. */
    private var parkedRecord: Triple<String, String, String>? = null // groupId, eventId, recordId
    private var parkedDate: String = ""
    private var lastLocation: Pair<Double, Double>? = null
    private var lastAddress: String = ""
    // ---- 12장 fuel
    private var carState = CarState()
    private var fuelAskedPercent: Double? = null   // fuel % when we last asked (Q33: ask again 10 points lower)
    private var fuelDeclinedForRoute = false
    private var fuelAskedToday: String? = null      // date of the last "not guiding" ask
    /** Q55: the fill-up planned at departure ("율동공원 지나서 ○○ 주유소"); while it stands, the plain low-fuel notice keeps quiet. */
    private var fuelPlan: GasStation? = null
    private var fuelPlanVersion = -1L
    private val _stations = MutableStateFlow<List<GasStation>>(emptyList())
    /** Cheapest stations near the car after "응" (12장); the screen lists the top 3. */
    val stations: StateFlow<List<GasStation>> = _stations.asStateFlow()
    /** The simulator is playing the day: position, remaining distance and ETA come from it, not the navigation. */
    @Volatile var simActive = false
    private var simEtaAt = 0L
    /** The car has driven since the last record: getting in (door / P at start) is not an arrival. */
    private var drovenSinceRecord = false
    private val _notice = MutableStateFlow<String?>(null)
    /** Short status for the screen ("기록 칸: 서현역 12:40"). */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _tripsToday = MutableStateFlow<List<com.htb.carplan.shared.data.CalendarEvent>>(emptyList())
    /** ⑩: every trip today (the car follows one of them; the screen offers the others while parked). */
    val tripsToday: StateFlow<List<com.htb.carplan.shared.data.CalendarEvent>> = _tripsToday.asStateFlow()

    /** ⑩ 주차 중 [다른 여행]: the car follows this trip from now on (cars/{id}.activeEventId — the latest choice wins). */
    fun chooseTrip(groupId: String, eventId: String) {
        val id = carId.value ?: return
        if (_guiding.value) { router.route(emptyList()); inNavi = emptyList(); _guiding.value = false }
        askedOnStart = false
        scope.launch { runCatching { cars.setActiveTrip(id, groupId, eventId) }.onFailure { _error.value = it.message } }
    }

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
                    _tripsToday.value = tripsToday
                    // ⑩: the latest explicit choice wins — the one chosen in the car (activeEventId), then a phone's
                    // "오늘 이 차로 가요" (7장 2), then the first trip of the day.
                    tripsToday.firstOrNull { it.event.id == car.activeEventId }
                        ?: tripsToday.firstOrNull { it.event.carId == car.id }
                        ?: tripsToday.firstOrNull()
                }
                .flatMapLatest { ev ->
                    if (ev == null) flowOf(null)
                    else combine(
                        trips.observeStops(ev.groupId, ev.event.id, today),
                        trips.observeDay(ev.groupId, ev.event.id, today),
                        pickupsRepo.observe(ev.groupId, ev.event.id, today).catch { emit(emptyList()) },
                    ) { stops, day, pickups -> TodayTrip(ev.groupId, ev.event.id, ev.event.title, today, stops.filterNot { it.deleted }, day, pickups) }
                }
        }
        .catch { _error.value = it.message; emit(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        router.addListener(object : NaviHelperEventListener {
            override fun onWayPointArrived(info: WaypointArrivedInfo) { Log.i(TAG, "waypoint arrived ${info.name}"); onArrived() }
            override fun onDestinationArrived(info: DestinationArrivedInfo) { Log.i(TAG, "destination arrived ${info.name}"); onArrived() }
            override fun onRouteStarted(info: RouteStartInfo) { onRouteStarted(info.duration) }
            override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
                if (simActive) return // the simulator owns the position (the emulator GPS never moves)
                lastLocation = info.latitude to info.longitude
                lastAddress = info.address
                _live.update { it.copy(lat = info.latitude, lng = info.longitude, heading = info.heading, address = info.address) }
            }
            override fun onTBTInfo(list: List<TBTInfo>) {
                val t = list.firstOrNull()
                _live.update { it.copy(turn = t?.type, turnMeters = t?.distance, turnText = t?.description) }
            }
            override fun onDrivingInfo(info: DrivingInfo) {
                if (simActive) return
                // The nearest point we are driving to: the first waypoint while any remain, else the destination.
                val next = info.waypoints.firstOrNull() ?: info.destination
                _live.update { it.copy(remainMeters = next.distance, remainSeconds = next.duration, nextName = next.name) }
                publishEta(next.duration)
            }
            override fun onRouteCancelled() {
                // Our own re-request cancels the previous route first; only a cancel we did not cause ends guiding.
                if (System.currentTimeMillis() - lastRouteSentAt < OWN_CANCEL_WINDOW_MS) return
                Log.i(TAG, "route cancelled by navigation")
                inNavi = emptyList()
                _guiding.value = false
            }
        })
        scope.launch { guiding.collect { on -> if (on) startEtaLoop() else etaJob?.cancel() } }
        scope.launch { guiding.collect { updateLiveLoop() } }
        // The fuel check only ran when the fuel value changed: a low tank seen while the start question was up was
        // never asked about. Look again whenever the screen is free of questions and when guiding starts or stops.
        scope.launch { combine(_prompt, guiding) { p, _ -> p == null }.collect { free -> if (free) onCarState(carState) } }
        scope.launch { carId.filterNotNull().flatMapLatest { calendar.observeCar(it) }.catch { }.collect { fuelAlertPercent = it?.fuelAlertPercent?.toDouble() ?: LOW_FUEL_PERCENT } }
        scope.launch {
            trip.filterNotNull().collect { t ->
                // 9장 1: ask once when the app starts and today has stops left.
                if (!askedOnStart && (t.next != null || t.waitingPickups.isNotEmpty())) { askedOnStart = true; ask(PromptReason.APP_START) }
                // 11장 P2: the app restarted while sharing was on → keep sharing (the question is skipped then).
                if (t.day?.pickupSharing == true && t.waitingPickups.isNotEmpty() && shareJob?.isActive != true) startSharing(t)
                // 10장: the plan changed under an active route → wait 5 s for more edits, then compare.
                if (_guiding.value && routedVersion >= 0 && t.version != routedVersion && t.version != revertedVersion) scheduleChangeCheck()
                // A commute offer made while the trip was (briefly) empty gives way once the trip has stops again (9장 first).
                if (_prompt.value?.reason == PromptReason.COMMUTE && (t.next != null || t.waitingPickups.isNotEmpty())) { askJob?.cancel(); commuteAskedDate = null; closePrompt(); askedOnStart = false }
                // "다음 목적지 ○○로 갈까요?" follows the plan: when the next stop changed (edited on a phone), ask about the new one.
                _prompt.value?.let { p ->
                    val start = p.reason == PromptReason.APP_START || p.reason == PromptReason.GEAR_D || p.reason == PromptReason.MANUAL
                    val n = t.next
                    if (start && n != null && n.id != p.stop.id) { ask(p.reason); return@collect }
                }
                // Drop a "다음 목적지" prompt whose stop vanished (a change prompt is handled above).
                _prompt.value?.let { p -> if (p.reason != PromptReason.ROUTE_CHANGE && p.reason != PromptReason.FUEL && p.reason != PromptReason.FUELED && p.reason != PromptReason.PICKUP && p.reason != PromptReason.COMMUTE && t.stops.none { it.id == p.stop.id && !it.visited }) dismiss() }
            }
        }
    }

    fun bind(carId: String) { this.carId.value = carId }

    /**
     * Puts [p] on screen now if nothing is there or it outranks what is; otherwise it waits its turn (묶음 7). [start]
     * is what speaks/listens for it and runs when it actually appears. Start/pickup questions beat notices; a route
     * change beats everything (the navigation already turned). Same reason twice → the newer replaces the older.
     */
    private fun show(p: NextPrompt, start: () -> Unit) {
        val cur = _prompt.value
        waiting.removeAll { it.first.reason == p.reason }
        // Same reason again (the next stop changed under a start question): the newer one takes the screen.
        if (cur == null || rank(p.reason) > rank(cur.reason) || cur.reason == p.reason) {
            if (cur != null && cur.single) waiting.addFirst(cur to {}) // a bumped notice comes back; a bumped question is dropped (stale answer)
            askJob?.cancel()
            _prompt.value = p
            start()
        } else waiting.addLast(p to start)
    }

    /** The prompt on screen is gone: the next one waiting comes up. Call after every `closePrompt()`. */
    private fun closePrompt() { _prompt.value = null; takeNext() }

    private fun takeNext() {
        if (_prompt.value != null) return
        // A start question that waited is stale once the car is already guiding (it was answered by another one).
        if (_guiding.value) waiting.removeAll { it.first.reason in START_REASONS }
        val (p, start) = waiting.removeFirstOrNull() ?: return
        _prompt.value = p
        start()
    }

    private fun rank(r: PromptReason) = when (r) {
        PromptReason.ROUTE_CHANGE -> 5
        PromptReason.APP_START, PromptReason.GEAR_D, PromptReason.MANUAL, PromptReason.PICKUP, PromptReason.COMMUTE -> 4
        PromptReason.FUELED -> 3
        PromptReason.FUEL -> 2
        PromptReason.PARKING -> 1
    }

    /** Gear events from [VehicleMonitor]: P → D asks "다음 목적지로 갈까요?"; D → P re-asks a held change (Q24). */
    fun onGear(gear: Gear) {
        val prev = lastGear
        lastGear = gear
        if (prev == Gear.P && gear == Gear.D && !_guiding.value && trip.value?.next != null) ask(PromptReason.GEAR_D)
        // 13장: P starts the 5-minute fallback; leaving P closes the record and resets.
        if (gear == Gear.P && prev != Gear.P) {
            // Parked = arrived: "근처 주차장 찾아볼까요?" is no longer a question.
            if (_prompt.value?.reason == PromptReason.PARKING) { askJob?.cancel(); closePrompt() }
            fuelAtPark = carState.fuelPercent; fueledAsked = false
            router.requestCurrentLocation()
            parkTimer?.cancel()
            if (drovenSinceRecord) parkTimer = scope.launch { delay(PARK_CONFIRM_MS); confirmArrival(reason = "P 5분") }
        }
        if (prev == Gear.P && gear != Gear.P) {
            drovenSinceRecord = true
            parkTimer?.cancel()
            parkedRecord?.let { (g, e, r) -> scope.launch { runCatching { records.markLeft(g, e, parkedDate, r) } } }
            parkedRecord = null
        }
    }

    /** Driver door opened (from [VehicleMonitor]): in P that means "내렸다" → arrival now (Q34). */
    fun onDriverDoorOpened() {
        if (lastGear == Gear.P && drovenSinceRecord) { parkTimer?.cancel(); confirmArrival(reason = "운전석 문") }
    }

    /** Driver's belt unbuckled while parked (13장 2): same meaning as the door — about to get out. */
    fun onDriverUnbuckled() {
        if (lastGear == Gear.P && drovenSinceRecord) { parkTimer?.cancel(); confirmArrival(reason = "운전석 벨트") }
    }

    /**
     * Makes the record for this parking (once): linked to the next planned stop when within
     * [NEAR_METERS] of it (or when we cannot measure and a route is active), otherwise unplanned;
     * on a day without a trip, under the owner's "일상 기록" event (Q37).
     */
    private fun confirmArrival(reason: String, assumeAtNext: Boolean = false) {
        if (parkedRecord != null) return
        drovenSinceRecord = false
        scope.launch {
            val t = trip.value
            val today = LocalDate.now().toString()
            val loc = lastLocation
            val next = t?.next
            val nearNext = next != null && (assumeAtNext || (loc != null && next.place.hasCoords() && RouteMath.metersBetween(loc.first, loc.second, next.place.lat, next.place.lng) <= NEAR_METERS) || (loc == null && _guiding.value))
            runCatching {
                val carUid = carId.value.orEmpty()
                if (t != null && nearNext && next != null) {
                    // ★3 주차 위치: where the car actually stopped (may differ from the stop's own point).
                    val id = records.addRecord(t.groupId, t.eventId, today, Record(place = next.place, stopId = next.id, createdBy = carUid, parkedLat = loc?.first, parkedLng = loc?.second))
                    parkedRecord = Triple(t.groupId, t.eventId, id); parkedDate = today
                    _notice.value = "기록 칸: ${next.place.name} ($reason)"
                    scope.launch { voice.say("${next.place.name} 도착. 기록 남겼어요.") }
                    onArrived() // ✓ + refill route
                } else {
                    // 13장: "건천휴게소" rather than the navigation's street address, when Kakao knows the spot.
                    val known = loc?.let { (la, lo) -> runCatching { kakao.nearestPlace(la, lo) }.getOrNull() }
                    val place = Place(name = known?.name ?: lastAddress.ifBlank { "이름 없는 곳" }, lat = loc?.first ?: 0.0, lng = loc?.second ?: 0.0, address = known?.address?.ifBlank { null } ?: lastAddress)
                    val (g, e) = if (t != null) t.groupId to t.eventId else dailyLogTarget(today) ?: return@runCatching
                    // Stopping again where we just stopped (traffic, a second look) is the same record, not a new one.
                    val again = records.recentUnplannedNear(g, e, today, place.lat, place.lng, NEAR_METERS, SAME_PARKING_MS)
                    if (again != null) { parkedRecord = Triple(g, e, again.id); parkedDate = today; return@runCatching }
                    val id = records.addRecord(g, e, today, Record(place = place, unplanned = true, createdBy = carUid, parkedLat = loc?.first, parkedLng = loc?.second))
                    parkedRecord = Triple(g, e, id); parkedDate = today
                    _notice.value = "계획 밖 기록 칸: ${place.name} ($reason)"
                    scope.launch { voice.say("계획에 없던 곳이에요. ${place.name}에 기록 남겼어요.") }
                }
            }.onFailure { _error.value = it.message }
        }
    }

    /** Q37: the owner's solo group + its "일상 기록" event for [date]. */
    private suspend fun dailyLogTarget(date: String): Pair<String, String>? {
        val car = carId.value?.let { calendar.observeCar(it).first() } ?: return null
        val owner = car.ownerUid ?: return null
        val user = groups.user(owner) ?: return null
        if (user.soloGroupId.isBlank()) return null
        return user.soloGroupId to records.ensureDailyLog(user.soloGroupId, date, owner)
    }

    /** The 🎤 button: ask again now. */
    fun askNow() = ask(PromptReason.MANUAL)

    // ---------- ⑧ 음성 명령 ----------

    private val _voice = MutableStateFlow(VoiceState())
    /** [말하기] on the car screen: listening, or showing the commands it knows after it did not understand. */
    val voiceState: StateFlow<VoiceState> = _voice.asStateFlow()
    private var voiceJob: Job? = null

    /** [말하기]: listen once and do what was said; nothing understood → say the commands and show them as buttons. */
    fun listenForCommand() {
        voiceJob?.cancel()
        // The driver pressed [말하기]: a question still listening lets go of the mic (its buttons stay, Q21).
        if (_prompt.value?.single != true) { askJob?.cancel(); _prompt.update { it?.copy(listening = false) } }
        voiceJob = scope.launch {
            _voice.value = VoiceState(listening = true)
            val heard = voice.listenOnce("말씀하세요.") { on -> _voice.update { it.copy(listening = on) } }
            val cmd = VoiceCommand.parse(heard)
            if (cmd != null) { _voice.value = VoiceState(); runCommand(cmd); return@launch }
            _voice.value = VoiceState(choices = true, heard = heard)
            voice.say(if (heard.isNullOrBlank()) VoiceCommand.HELP else "잘 못 들었어요. ${VoiceCommand.HELP}")
            delay(VOICE_CHOICES_MS)
            _voice.update { if (it.choices) VoiceState() else it }
        }
    }

    fun closeVoice() { voiceJob?.cancel(); _voice.value = VoiceState() }

    /** One spoken (or tapped) command. Each answers out loud — the driver is not looking. */
    fun runCommand(cmd: VoiceCommand) {
        _voice.value = VoiceState()
        val t = trip.value
        when (cmd) {
            VoiceCommand.FUEL -> { scope.launch { voice.say(if (carState.isEv == true) "충전소를 찾을게요." else "주유소를 찾을게요.") }; fuelPlan?.let { goViaStation(it) } ?: findStation() }
            VoiceCommand.PARKING -> { scope.launch { voice.say("근처 주차장을 찾을게요.") }; router.search("주차장") }
            VoiceCommand.WHERE -> scope.launch { voice.say(whereSentence(t)) }
            VoiceCommand.REVERT -> if (previousRoute.isNotEmpty() && _guiding.value) revertRoute() else scope.launch { voice.say("되돌릴 경로가 없어요.") }
            VoiceCommand.SKIP -> skipNext(t)
            VoiceCommand.START -> if (_guiding.value) scope.launch { voice.say("이미 안내 중이에요. ${whereSentence(t)}") } else if (t?.next != null) go() else scope.launch { voice.say("오늘 갈 곳이 없어요.") }
        }
    }

    /** "다음은 율동공원, 12분 뒤 15:23 도착이에요. 그다음은 정자역이에요." */
    private fun whereSentence(t: TodayTrip?): String {
        val next = t?.next ?: return "오늘 남은 일정이 없어요."
        val secs = _live.value.remainSeconds?.takeIf { _guiding.value }
        val eta = secs?.let { LocalTime.now().plusSeconds(it.toLong()) }?.let { "%d시 %d분".format(it.hour, it.minute) }
        val time = if (secs != null && eta != null) " ${(secs / 60).coerceAtLeast(1)}분 뒤 $eta 도착이에요." else next.time?.let { " $it 예정이에요." } ?: "예요."
        val after = t.remaining.getOrNull(1)?.let { " 그다음은 ${it.place.name}이에요." }.orEmpty()
        return "다음은 ${next.place.name}.$time$after"
    }

    /** "건너뛰어": the next stop is ticked off without a record and the navigation goes on to the one after. */
    private fun skipNext(t: TodayTrip?) {
        val next = t?.next ?: run { scope.launch { voice.say("건너뛸 곳이 없어요.") }; return }
        val after = t.remaining.filterNot { it.id == next.id }
        scope.launch {
            voice.say("${next.place.name}은 건너뛸게요." + (after.firstOrNull()?.let { " ${withRo(it.place.name)} 갈게요." } ?: ""))
            runCatching { trips.setVisited(t.groupId, t.eventId, t.date, next.id, true, t.stops.indexOfFirst { it.id == after.firstOrNull()?.id }.coerceAtLeast(0)) }
            if (after.isEmpty()) { router.route(emptyList()); inNavi = emptyList(); _guiding.value = false; return@launch }
            if (_guiding.value) sendRoute(t.copy(stops = t.stops.map { if (it.id == next.id) it.copy(visited = true) else it }))
        }
    }

    /** 16장: a commute is due now. Asked once per day unless there is a trip (9장 asks first). */
    fun offerCommute(e: Event) {
        commuteEvent = e
        val today = LocalDate.now().toString()
        if (commuteAskedDate == today || commuteDeclinedDate == today) return
        if (trip.value?.next != null || _guiding.value) return
        val place = e.place ?: return
        commuteAskedDate = today
        val message = "${withRo(place.name)} 갈까요?"
        show(NextPrompt(Stop(place = place), PromptReason.COMMUTE, message = message)) { askJob = scope.launch { answer(voice.ask(message, ::listening)) } }
    }

    private fun listening(listening: Boolean, heard: String?) { _prompt.update { it?.copy(listening = listening, heard = heard ?: it.heard) } }
    private fun answer(a: VoicePrompt.Answer) = when (a) {
        VoicePrompt.Answer.YES -> go()
        VoicePrompt.Answer.NO -> later()
        VoicePrompt.Answer.UNKNOWN -> _prompt.update { it?.copy(listening = false) } // buttons stay (Q21)
    }

    // ---------- 12장: fuel / charge ----------

    /** Latest car state from the service (fuel %, range, EV?). Decides whether to ask about a station. */
    /** 주행 화면 needs position + turns while a route is active or the car is moving (view-only mode). */
    private var liveOn = false
    private fun updateLiveLoop() {
        val on = _guiding.value || carState.drivingRestricted
        if (on == liveOn) return
        liveOn = on
        liveJob?.cancel()
        if (on) liveJob = scope.launch { while (true) { router.requestCurrentLocation(); router.requestTbt(); delay(LIVE_INTERVAL_MS) } }
        else _live.value = NaviLive()
    }

    fun onCarState(state: CarState) {
        carState = state
        updateLiveLoop()
        val fuel = state.fuelPercent ?: return
        // A station question that is still open when the tank is clearly fuller is stale: drop it (the fill-up question follows).
        _prompt.value?.let { p -> if (p.reason == PromptReason.FUEL && fuel >= (fuelAskedPercent ?: 0.0) + 5) { askJob?.cancel(); closePrompt() } }
        // 15장 Q44: parked and the tank/battery went up ≥ 5 points → "주유했어요?" once per parking.
        if (lastGear == Gear.P) {
            val base = fuelAtPark?.let { minOf(it, fuel) }?.also { fuelAtPark = it } ?: fuel.also { fuelAtPark = it }
            if (!fueledAsked && fuel >= base + 5) { fueledAsked = true; askFueled(state.isEv == true) }
        }
        val range = state.rangeKm
        val t = trip.value
        val today = LocalDate.now().toString()
        val remainingKm = t?.remaining?.let { remainingKm(it) }
        val need = when {
            _guiding.value && range != null && range > 0 && remainingKm != null -> range < remainingKm + ROUTE_MARGIN_KM
            else -> fuel <= fuelAlertPercent || (range != null && range > 0 && range < LOW_RANGE_KM * fuelAlertPercent / LOW_FUEL_PERCENT)
        }
        if (!need) return
        if (fuelPlanVersion == t?.version) return // Q55: this plan version was already looked at (a station found, or enough fuel)
        fuelAskedPercent?.let { asked -> if (fuel > asked - 10) return } // Q33
        if (!_guiding.value && fuelAskedToday == today && fuelAskedPercent != null && fuel > fuelAskedPercent!! - 10) return
        if (_guiding.value && fuelDeclinedForRoute && fuel > (fuelAskedPercent ?: 101.0) - 10) return
        fuelAskedPercent = fuel
        fuelAskedToday = today
        askFuel(state.isEv == true, fuel, range, remainingKm)
    }

    private fun askFuel(ev: Boolean, fuel: Double, range: Double?, remainingKm: Double?) {
        val what = if (ev) "충전소" else "주유소"
        // A range of 0 means the car does not report one (the emulator): fall back to the percent wording.
        val why = if (remainingKm != null && range != null && range > 0 && _guiding.value) "남은 길 ${remainingKm.toInt()}km인데 ${range.toInt()}km밖에 못 가요" else "${if (ev) "배터리" else "연료"}가 ${fuel.toInt()}%예요"
        // Not a question (차 화면 원칙): say it once, leave one button; passengers can look on their phones.
        val message = "$why. ${what}${ga(what)} 필요해요."
        show(NextPrompt(trip.value?.next ?: Stop(), PromptReason.FUEL, message = message, single = true, button = "$what 찾기")) { askJob = scope.launch { voice.say(message) } }
    }

    private fun askFueled(ev: Boolean) {
        val message = if (ev) "충전했어요? 충전 기록을 남길까요?" else "주유했어요? 주유 기록을 남길까요?"
        show(NextPrompt(trip.value?.next ?: Stop(), PromptReason.FUELED, message = message)) { askJob = scope.launch { answer(voice.ask(message, ::listening)) } }
    }

    /** "네" to 주유했어요?: a 주유 record in the owner's solo group with today's odometer (15장). */
    private fun logFuel() {
        scope.launch {
            runCatching {
                val car = carId.value?.let { calendar.observeCar(it).first() } ?: return@launch
                val owner = car.ownerUid ?: return@launch
                val user = groups.user(owner) ?: return@launch
                val st = pickedStation?.also { pickedStation = null }
                val memo = st?.let { "${it.name} · ${"%,d".format(it.price)}원/L" }.orEmpty()
                maintenance.log(user.soloGroupId, car.id, MaintenanceItem.FUEL, LocalDate.now(), carState.odometerKm, memo = memo, byUid = owner, auto = true, intervals = car.intervals)
                _notice.value = (if (carState.isEv == true) "충전" else "주유") + " 기록 남김 — 금액은 폰 차량 탭에서"
            }.onFailure { _error.value = it.message }
        }
    }

    /** 11장 Q27: pickups exist → one question that both starts the route and turns sharing on. */
    private fun askPickup(t: TodayTrip) {
        val names = t.waitingPickups.joinToString(", ") { it.name.ifBlank { "일행" } }
        val message = "$names 태우러 가요. 위치를 일행에게 보여 줄까요?"
        show(NextPrompt(t.next ?: Stop(place = t.waitingPickups.first().place), PromptReason.PICKUP, message = message)) { askJob = scope.launch { answer(voice.ask(message, ::listening)) } }
    }

    /** Sharing loop: every 5 s ask the navigation for the position and publish it, until nobody is waiting. */
    private fun startSharing(t: TodayTrip) {
        shareJob?.cancel()
        shareJob = scope.launch {
            runCatching { pickupsRepo.setSharing(t.groupId, t.eventId, t.date, true) }
            while (true) {
                router.requestCurrentLocation()
                delay(SHARE_INTERVAL_MS)
                val cur = trip.value ?: break
                val open = cur.pickups.filter { it.status != PickupStatus.DONE }
                // 11장 7: 10 minutes after the car arrived, that pickup is over.
                open.filter { it.status == PickupStatus.WAITING && it.arrivedAt != null && System.currentTimeMillis() - it.arrivedAt!!.toDate().time > PICKUP_DONE_MS }
                    .forEach { p -> runCatching { pickupsRepo.setStatus(cur.groupId, cur.eventId, cur.date, p.uid, PickupStatus.DONE) } }
                if (open.none { it.status == PickupStatus.WAITING }) break
                lastLocation?.let { (la, lo) -> carId.value?.let { id -> runCatching { cars.updateLocation(id, la, lo) } } }
            }
            stopSharing(trip.value)
        }
    }

    private suspend fun stopSharing(t: TodayTrip?) {
        if (t != null) runCatching { pickupsRepo.setSharing(t.groupId, t.eventId, t.date, false) }
        carId.value?.let { id -> runCatching { cars.updateLocation(id, null, null) } }
        _notice.value = "픽업 끝 · 위치 공유 껐어요"
    }

    /**
     * 12장 Q55 (가벼운 판): at departure, add up the day's legs by road (one Kakao call per leg, cached), see where the
     * tank runs short, and look for the cheapest station near that one point — one Opinet call, once per plan.
     * The result is spoken ("율동공원 지나서 ○○ 주유소에서 넣으면 좋아요") and offered as a notice with one button.
     * Electric cars get the navigation's own charger search instead of a price.
     */
    private fun planFuel(t: TodayTrip) {
        if (fuelPlanVersion == t.version && fuelPlan != null) return
        val fuel = carState.fuelPercent ?: return
        val reach = carState.rangeKm?.takeIf { it > 0 }?.times(1000.0) ?: return
        val stops = t.remaining.filter { it.place.hasCoords() }
        if (stops.isEmpty()) return
        // Claim this version now so the plain low-fuel notice (onCarState) keeps quiet while the legs are measured.
        fuelPlanVersion = t.version
        _prompt.value?.takeIf { it.reason == PromptReason.FUEL }?.let { askJob?.cancel(); closePrompt() }
        waiting.removeAll { it.first.reason == PromptReason.FUEL }
        scope.launch {
            val legs = ArrayList<FuelPlan.Leg>()
            var from = lastLocation?.let { Place(name = "차", lat = it.first, lng = it.second) } ?: stops.first().place
            for (s in stops) {
                if (from.lat == s.place.lat && from.lng == s.place.lng) { from = s.place; continue }
                val m = runCatching { kakao.directions(from, s.place).meters.toDouble() }.getOrElse { RouteMath.metersBetween(from.lat, from.lng, s.place.lat, s.place.lng) * 1.3 }
                legs += FuelPlan.Leg(from, s, m)
                from = s.place
            }
            val short = FuelPlan.shortfall(legs, reach, ROUTE_MARGIN_KM * 1000) ?: run { fuelPlan = null; return@launch }
            val after = FuelPlan.afterName(legs, short)
            val where = after?.let { "${it} 지나서" } ?: "출발하고 바로"
            val ev = carState.isEv == true
            if (ev || !opinet.available) {
                val what = if (ev) "충전소" else "주유소"
                val message = "오늘 남은 길이 ${(short.total / 1000).toInt()}km인데 ${(reach / 1000).toInt()}km밖에 못 가요. $where ${what}에 들러야 해요."
                show(NextPrompt(t.next ?: Stop(), PromptReason.FUEL, message = message, single = true, button = "$what 찾기")) { askJob = scope.launch { voice.say(message) } }
                return@launch
            }
            val at = FuelPlan.pointAt(legs, short.byMeters) ?: return@launch
            val list = runCatching { opinet.around(at.lat, at.lng) }.getOrElse { emptyList() }
            val best = list.minByOrNull { it.price } ?: run {
                val message = "오늘 남은 길이 ${(short.total / 1000).toInt()}km인데 ${(reach / 1000).toInt()}km밖에 못 가요. $where 주유소에 들러야 해요."
                show(NextPrompt(t.next ?: Stop(), PromptReason.FUEL, message = message, single = true, button = "주유소 찾기")) { askJob = scope.launch { voice.say(message) } }
                return@launch
            }
            fuelPlan = best
            _stations.value = emptyList()
            val message = "연료가 ${fuel.toInt()}%라 $where ${best.name}에서 넣으면 좋아요. 리터당 ${"%,d".format(best.price)}원이에요."
            show(NextPrompt(t.next ?: Stop(), PromptReason.FUEL, message = message, single = true, button = "경유지로 넣기")) { askJob = scope.launch { voice.say(message) } }
        }
    }

    /** "응" to the station question: Opinet cheapest list (fuel car + key), else the navigation's own search. */
    private fun findStation() {
        val ev = carState.isEv == true
        router.requestCurrentLocation()
        scope.launch {
            delay(600) // let the location answer arrive
            val loc = lastLocation
            if (!ev && opinet.available && loc != null) {
                runCatching { opinet.around(loc.first, loc.second) }
                    .onSuccess { list ->
                        // Q30: cheapest first, but only ones reasonably close (within 3 km of the car)
                        val near = list.filter { it.meters <= 3_000 }.ifEmpty { list }
                        _stations.value = near.take(3)
                        if (near.isEmpty()) router.search("주유소")
                    }
                    .onFailure { _error.value = it.message; router.search("주유소") }
            } else {
                router.search(if (ev) "충전소" else "주유소")
            }
        }
    }

    /** Driver picked a station from the list: it becomes the next waypoint, the plan follows. */
    fun goViaStation(st: GasStation) {
        _stations.value = emptyList()
        pickedStation = st
        // The station goes in front; behind it the places still to go (pickups waiting, then stops not yet visited —
        // never one ticked off while the list was open). Arriving at the station ticks nothing (N1).
        val t = trip.value
        val tail = (t?.waitingPickups.orEmpty().map { NaviEntry(NaviEntry.Kind.PICKUP, it.uid, it.place) } +
            t?.remaining.orEmpty().filter { it.place.hasCoords() }.map { NaviEntry(NaviEntry.Kind.STOP, it.id, it.place) }).take(MAX_PLACES - 1)
        inNavi = listOf(NaviEntry(NaviEntry.Kind.STATION, st.id, st.place)) + tail
        router.route(inNavi.map { it.place })
        router.openNavigation()
        lastRouteSentAt = System.currentTimeMillis()
        _notice.value = "${st.name} (${st.price}원) 들렀다 가요"
        fuelDeclinedForRoute = false
    }

    fun dismissStations() { _stations.value = emptyList() }

    private fun remainingKm(stops: List<Stop>): Double? {
        val withCoords = stops.filter { it.place.hasCoords() }
        if (withCoords.isEmpty()) return null
        var km = 0.0
        lastLocation?.let { (la, lo) -> km += RouteMath.metersBetween(la, lo, withCoords.first().place.lat, withCoords.first().place.lng) / 1000 }
        withCoords.zipWithNext().forEach { (a, b) -> km += (RouteMath.meters(a.place, b.place) ?: 0.0) / 1000 }
        return km * 1.3
    }

    // ---------- 시뮬레이션 (DriveSimulator) ----------

    fun simLocation(): Pair<Double, Double>? = lastLocation

    /** The simulated car moved: same effect as a navigation location + driving-info update. */
    fun simMove(lat: Double, lng: Double, remainMeters: Int, remainSeconds: Int, nextName: String) {
        lastLocation = lat to lng
        _live.update { it.copy(lat = lat, lng = lng, remainMeters = remainMeters, remainSeconds = remainSeconds, nextName = nextName) }
        publishEta(remainSeconds)
    }

    /** Parked: the leg is over, so no "0 m · 1분" left on screen. */
    fun simParked() = _live.update { it.copy(remainMeters = null, remainSeconds = null) }

    /** "응" to the start question, if that is what is on screen (not a fuel or change question). */
    fun simAnswerStart() {
        val p = _prompt.value
        if (p == null) { if (!_guiding.value) go(); return }
        if (p.reason in setOf(PromptReason.APP_START, PromptReason.GEAR_D, PromptReason.MANUAL, PromptReason.PICKUP)) go()
    }

    /** 11장 phones: "약 n분" for the first pickup (every 5 s, not every tick). */
    fun simEta(remainSeconds: Int, isPickup: Boolean) {
        val now = System.currentTimeMillis()
        if (!isPickup || now - simEtaAt < SHARE_INTERVAL_MS) return
        simEtaAt = now
        val t = trip.value ?: return
        val uid = t.waitingPickups.firstOrNull()?.uid ?: return
        scope.launch { runCatching { pickupsRepo.setEta(t.groupId, t.eventId, t.date, uid, remainSeconds.toLong()) } }
    }

    /** 12장: five minutes before the next stop → "주차장 찾아봐 주세요" push. */
    fun simParkingHint() {
        val t = trip.value ?: return
        val n = t.next ?: return
        if (t.day?.parkingHintStopId != n.id) { scope.launch { runCatching { trips.setParkingHint(t.groupId, t.eventId, t.date, n.id) } }; askParking(n) }
    }

    /** The car reached a pickup place (what the navigation's waypoint event does on a real road). */
    fun simArrivedPickup(uid: String) {
        val t = trip.value ?: return
        inNavi = inNavi.filterNot { it.kind == NaviEntry.Kind.PICKUP && it.id == uid }
        scope.launch {
            runCatching { pickupsRepo.markArrived(t.groupId, t.eventId, t.date, uid) }
            val who = t.pickups.firstOrNull { it.uid == uid }?.name?.ifBlank { null } ?: "일행"
            _notice.value = "픽업 장소 도착 · $who"
            voice.say("$who 픽업 장소예요.")
        }
    }

    /** What the navigation is driving to first (the simulator follows the same list the car does). */
    fun simTarget(): NaviEntry? = inNavi.firstOrNull()

    /** The simulated car reached the petrol station in front of the route: take it off the list, nothing is ticked. */
    fun simArrivedStation() { if (inNavi.firstOrNull()?.kind == NaviEntry.Kind.STATION) onArrived() }

    /** The passenger got in (phones normally press [탔어요]). */
    fun simBoarded(uid: String) {
        val t = trip.value ?: return
        scope.launch { runCatching { pickupsRepo.setStatus(t.groupId, t.eventId, t.date, uid, PickupStatus.ON_BOARD) } }
    }

    /** A stop the simulator cannot drive to (no coordinates): tick it off and move on. */
    fun simSkip() = onArrived()

    private fun ask(reason: PromptReason) {
        val t = trip.value ?: return
        if (t.waitingPickups.isNotEmpty() && t.day?.pickupSharing != true) { askPickup(t); return }
        val next = t.next ?: return
        fuelPlanVersion = t.version // Q55: the plan speaks after "가기"; the plain notice stays quiet meanwhile
        show(NextPrompt(next, reason)) {
            askJob = scope.launch {
                // ★1 (차 쪽): "지금 출발하면 10시 12분 도착이에요" — one Kakao leg, cached (legSeconds), straight line when offline.
                val eta = departEta(next)
                eta?.let { e -> _prompt.update { p -> p?.takeIf { it.stop.id == next.id }?.copy(message = "지금 출발하면 $e 도착") } }
                val q = "다음 목적지 ${withRo(next.place.name)} 갈까요?" + (eta?.let { " 지금 출발하면 ${it.replace(":", "시 ")}분 도착이에요." } ?: "")
                answer(voice.ask(q, ::listening))
            }
        }
    }

    /** "10:12" when leaving now for [next] (needs the car's position); null when it cannot tell. */
    private suspend fun departEta(next: Stop): String? {
        if (!next.place.hasCoords()) return null
        val here = lastLocation ?: return null
        val secs = legSeconds(Place(name = "차", lat = here.first, lng = here.second), next.place)
        val at = LocalTime.now().plusSeconds(secs.toLong())
        return "%d:%02d".format(at.hour, at.minute)
    }

    /** 12장: five minutes out — passengers get a push; the car offers the navigation's own parking search. */
    private fun askParking(next: Stop) {
        val message = "곧 ${next.place.name} 도착이에요."
        show(NextPrompt(next, PromptReason.PARKING, message = message, single = true, button = "근처 주차장 찾기")) { askJob = scope.launch { voice.say(message) } }
    }

    /** "응" / [가기] (9장) or [바꾸기] (10장): route the remaining stops and open the navigation. */
    fun go() {
        if (_prompt.value?.reason == PromptReason.PARKING) {
            askJob?.cancel(); closePrompt(); router.search("주차장"); return
        }
        if (_prompt.value?.reason == PromptReason.FUEL) {
            askJob?.cancel(); closePrompt()
            fuelPlan?.let { goViaStation(it); return }
            findStation(); return
        }
        if (_prompt.value?.reason == PromptReason.FUELED) {
            askJob?.cancel(); closePrompt(); logFuel(); return
        }
        if (_prompt.value?.reason == PromptReason.COMMUTE) {
            askJob?.cancel(); closePrompt()
            commuteEvent?.place?.let { p -> if (p.hasCoords()) { router.route(listOf(p)); router.openNavigation() } else router.search(p.name) }
            lastRouteSentAt = System.currentTimeMillis()
            _notice.value = "${commuteEvent?.title ?: "출근"} · ${commuteEvent?.place?.name} 안내"
            return
        }
        if (_prompt.value?.reason == PromptReason.ROUTE_CHANGE) { revertRoute(); return }
        waiting.removeAll { it.first.reason in START_REASONS } // one "가기" answers every start question
        val wasPickup = _prompt.value?.reason == PromptReason.PICKUP
        val t = trip.value ?: return
        if (wasPickup) startSharing(t)
        askJob?.cancel()
        closePrompt()
        revertedVersion = -1
        routeWithPickups(t)
        planFuel(t)
        scope.launch {
            runCatching {
                trips.setDayStatus(t.groupId, t.eventId, t.date, DayStatus.DRIVING)
                carId.value?.let { cars.setActiveTrip(it, t.groupId, t.eventId) }
                trips.setRouteSync(t.groupId, t.eventId, t.date, t.version, hold = false, etaNext = t.day?.etaNext)
            }.onFailure { _error.value = it.message }
        }
    }

    /** "아니" / [나중에] (9장): keep today's plan. For a notice (연료·주차장·경로 바뀜) just closes it. */
    fun later() {
        askJob?.cancel()
        if (_prompt.value?.reason == PromptReason.FUEL) { fuelDeclinedForRoute = true; closePrompt(); return }
        if (_prompt.value?.reason == PromptReason.FUELED || _prompt.value?.reason == PromptReason.PARKING || _prompt.value?.reason == PromptReason.ROUTE_CHANGE) { closePrompt(); return }
        if (_prompt.value?.reason == PromptReason.COMMUTE) { commuteDeclinedDate = LocalDate.now().toString(); closePrompt(); return }
        if (_prompt.value?.reason == PromptReason.PICKUP) {
            // "아니": still drive to the pickups, just without sharing the position.
            closePrompt()
            trip.value?.let { routeWithPickups(it); scope.launch { runCatching { trips.setDayStatus(it.groupId, it.eventId, it.date, DayStatus.DRIVING) } } }
            return
        }
        closePrompt()
    }

    fun dismiss() = later()

    /** Emulator has no moving GPS: the demo button plays "parked at the next stop + got out" (20장 debug). */
    fun debugArrive() {
        if (parkedRecord != null) { parkedRecord = null } // allow repeated demo taps
        parkTimer?.cancel()
        confirmArrival(reason = "시연", assumeAtNext = true)
    }

    fun clearNotice() { _notice.value = null }

    // ---------- 10장: plan changed while guiding ----------

    private fun scheduleChangeCheck(delayMs: Long = CHANGE_SETTLE_MS) {
        changeJob?.cancel()
        changeJob = scope.launch {
            delay(delayMs)
            val t = trip.value ?: return@launch
            if (!_guiding.value) return@launch
            val diff = describeChange(routedPlan, t.remaining)
            // What the navigation actually holds (the next few places): a change further back needs no new route.
            val stopsInNavi = inNavi.count { it.kind == NaviEntry.Kind.STOP }.coerceAtLeast(1)
            val ahead = describeChange(routedPlan.take(stopsInNavi), t.remaining.take(stopsInNavi))
            if (diff == null || ahead == null) {
                // A memo, a time, or a stop beyond the routed ones: the plan is reflected, the car says nothing.
                routedPlan = t.remaining
                routedVersion = t.version
                runCatching { trips.setRouteSync(t.groupId, t.eventId, t.date, t.version, hold = false, etaNext = t.day?.etaNext) }
                return@launch
            }
            val who = t.day?.lastChangedBy?.let { nameOf(it) } ?: "일행"
            applyChange(t, who, diff)
        }
    }

    /**
     * 10장 (정원 2026-10-31, Q23·Q24 바꿈): no question — the navigation takes the new plan right away, the car says
     * what changed, and [원래대로] stays on screen for [REVERT_WINDOW_MS] to put the old route back.
     */
    private fun applyChange(t: TodayTrip, who: String, diff: String) {
        val next = t.next
        if (next == null) {
            // Everything left was removed: stop guiding.
            router.route(emptyList()); inNavi = emptyList(); routedPlan = emptyList(); _guiding.value = false
            scope.launch { voice.say("${who}님이 남은 일정을 모두 뺐어요. 안내를 끝낼게요.") }
            return
        }
        val nextChanged = routedPlan.firstOrNull()?.id != next.id
        previousRoute = inNavi.map { it.place }
        previousVersion = routedVersion
        askJob?.cancel()
        revertedVersion = -1
        routeWithPickups(t)
        scope.launch {
            runCatching { trips.setRouteSync(t.groupId, t.eventId, t.date, t.version, hold = false, etaNext = t.day?.etaNext) }
        }
        val message = if (nextChanged) "${who}님이 $diff. 다음 목적지가 ${withRo(next.place.name)} 바뀌었어요." else "${who}님이 $diff. 경로를 바꿨어요."
        show(NextPrompt(next, PromptReason.ROUTE_CHANGE, message = message, single = true, button = "원래대로")) { askJob = scope.launch { voice.say(message) } }
        revertJob?.cancel()
        revertJob = scope.launch {
            delay(REVERT_WINDOW_MS)
            if (_prompt.value?.reason == PromptReason.ROUTE_CHANGE) closePrompt()
        }
    }

    /** [원래대로]: the navigation drives the previous places again; the plan on the phones stays as the group made it. */
    private fun revertRoute() {
        val t = trip.value ?: return
        askJob?.cancel(); revertJob?.cancel()
        closePrompt()
        if (previousRoute.isEmpty()) return
        router.route(previousRoute)
        lastRouteSentAt = System.currentTimeMillis()
        revertedVersion = t.version
        _notice.value = "이전 경로로 돌아갔어요 · 일정은 그대로"
        scope.launch {
            voice.say("이전 경로로 돌아갈게요.")
            runCatching { trips.setRouteSync(t.groupId, t.eventId, t.date, previousVersion.coerceAtLeast(0), hold = true, etaNext = t.day?.etaNext) }
        }
    }

    private suspend fun nameOf(uid: String): String =
        withTimeoutOrNull(3_000) { calendar.observeUser(uid).first()?.name?.takeIf { it.isNotBlank() } } ?: "일행"

    /**
     * 11장 2: with two or more people to collect, order them so the whole run (car → pickups → first stop)
     * takes the least time, using Kakao Mobility legs (straight line when offline), then route.
     */
    private fun routeWithPickups(t: TodayTrip) {
        val waiting = t.waitingPickups
        if (waiting.size < 2 || waiting.size > 4) { sendRoute(t); return }
        scope.launch {
            val ordered = bestPickupOrder(t, waiting)
            if (ordered.map { it.uid } != waiting.map { it.uid }) {
                runCatching { pickupsRepo.setOrder(t.groupId, t.eventId, t.date, ordered.map { it.uid } + t.pickups.map { it.uid }.filterNot { u -> ordered.any { it.uid == u } }) }
                _notice.value = "픽업 순서를 총 시간 기준으로 바꿨어요"
            }
            sendRoute(t, ordered)
        }
    }

    private suspend fun bestPickupOrder(t: TodayTrip, waiting: List<Pickup>): List<Pickup> {
        val here = lastLocation?.let { Place(name = "현재 위치", lat = it.first, lng = it.second) } ?: return waiting
        val goal = t.next?.place?.takeIf { it.hasCoords() }
        var best = waiting
        var bestCost = Int.MAX_VALUE
        for (p in permutations(waiting)) {
            var cost = 0
            var prev = here
            for (x in p) { cost += legSeconds(prev, x.place); prev = x.place }
            if (goal != null) cost += legSeconds(prev, goal)
            if (cost < bestCost) { bestCost = cost; best = p }
        }
        return best
    }

    private fun <T> permutations(list: List<T>): List<List<T>> =
        if (list.size <= 1) listOf(list) else list.indices.flatMap { i -> permutations(list.filterIndexed { j, _ -> j != i }).map { listOf(list[i]) + it } }

    /** Kakao driving time, cached; straight-line estimate when there is no key or no network. */
    private suspend fun legSeconds(a: Place, b: Place): Int {
        val key = "%.4f,%.4f>%.4f,%.4f".format(a.lat, a.lng, b.lat, b.lng)
        legCache[key]?.let { return it }
        val s = runCatching { kakao.directions(a, b).seconds }.getOrElse { RouteMath.estimateSeconds(RouteMath.metersBetween(a.lat, a.lng, b.lat, b.lng)) }
        legCache[key] = s
        return s
    }

    /**
     * While guiding, every 30 s: driving time from the car to the first pickup (→ pickups/{uid}.etaSeconds, phones
     * show "약 n분", Functions push "곧 도착" under 3 min) or to the next stop (≤ 5 min → parkingHintStopId → 12장
     * "주차장 찾아봐 주세요" push to the passengers).
     * The time comes from the navigation's own driving info (the nearest point it is driving to — the pickup when one
     * is in front); Kakao Mobility is only the fallback, at most every [KAKAO_ETA_MIN_MS], to keep the API quota.
     */
    private fun startEtaLoop() {
        etaJob?.cancel()
        etaJob = scope.launch {
            var kakaoAt = 0L
            while (true) {
                router.requestCurrentLocation()
                delay(2_000)
                val t = trip.value
                val here = lastLocation
                if (t != null && here != null && !simActive) {
                    val from = Place(name = "차", lat = here.first, lng = here.second)
                    suspend fun etaTo(place: Place): Int? = _live.value.remainSeconds
                        ?: if (System.currentTimeMillis() - kakaoAt >= KAKAO_ETA_MIN_MS) { kakaoAt = System.currentTimeMillis(); runCatching { kakao.directions(from, place).seconds }.getOrNull() } else null
                    val pickup = inNavi.firstOrNull()?.takeIf { it.kind == NaviEntry.Kind.PICKUP }?.let { e -> t.pickups.firstOrNull { it.uid == e.id && it.status == PickupStatus.WAITING && it.place.hasCoords() } }
                    if (pickup != null) {
                        val eta = etaTo(pickup.place)
                        if (eta != null) runCatching { pickupsRepo.setEta(t.groupId, t.eventId, t.date, pickup.uid, eta.toLong()) }
                    } else {
                        val next = t.next?.takeIf { it.place.hasCoords() }
                        if (next != null && t.day?.parkingHintStopId != next.id) {
                            val eta = etaTo(next.place)
                            if (eta != null && eta <= PARKING_HINT_S) { runCatching { trips.setParkingHint(t.groupId, t.eventId, t.date, next.id) }; askParking(next) }
                        }
                    }
                }
                delay(ETA_INTERVAL_MS - 2_000)
            }
        }
    }

    private fun sendRoute(t: TodayTrip, pickups: List<Pickup> = t.waitingPickups) {
        val pick = pickups.take(MAX_PLACES)
        val batch = t.remaining.take((MAX_PLACES - pick.size).coerceAtLeast(0))
        routedPlan = t.remaining
        routedVersion = t.version
        if (batch.isEmpty() && pick.isEmpty()) {
            inNavi = emptyList()
            _guiding.value = false
            return
        }
        // A name-only stop (no coordinates) cannot ride in a route: route up to it, and when it is the next one
        // hand its name to the navigation's own search — never drop it quietly (2026-10-31 bug).
        val withCoords = batch.takeWhile { it.place.hasCoords() }
        if (withCoords.isEmpty() && pick.isEmpty()) {
            val first = batch.first()
            router.search(first.place.name)
            inNavi = listOf(NaviEntry(NaviEntry.Kind.STOP, first.id, first.place))
            _notice.value = "${first.place.name}: 주소가 없어 내비 검색으로 찾아요"
            scope.launch { voice.say("${first.place.name}은 주소가 없어서 내비에서 찾아 주세요.") }
        } else {
            // 11장 3: pickups ride in front of the planned stops.
            inNavi = pick.map { NaviEntry(NaviEntry.Kind.PICKUP, it.uid, it.place) } + withCoords.map { NaviEntry(NaviEntry.Kind.STOP, it.id, it.place) }
            router.route(inNavi.map { it.place })
            // While simulating, keep our screen in front (the route still goes to the navigation).
            if (!simActive) router.openNavigation()
        }
        lastRouteSentAt = System.currentTimeMillis()
        _guiding.value = true
        Log.i(TAG, "routed ${inNavi.count { it.kind == NaviEntry.Kind.STOP }} of ${t.remaining.size} remaining (version ${t.version})")
    }

    /** The navigation computed a route: publish when it expects to reach the next stop (10장 7·8). */
    private fun onRouteStarted(durationSeconds: Int) {
        Log.i(TAG, "route started, next in ${durationSeconds}s")
        publishEta(durationSeconds, force = true)
    }

    private var etaPublishedAt = 0L
    private var etaPublished: String? = null
    /**
     * 묶음 5: the car's ETA is the one source phones read (days/{date}.etaNext, pickups/{uid}.etaSeconds come from the
     * same number). Written when the minute changes, at most every [ETA_PUBLISH_MS], so the day document is not spammed.
     */
    private fun publishEta(seconds: Int?, force: Boolean = false) {
        val t = trip.value ?: return
        seconds ?: return
        val eta = LocalTime.now().plusSeconds(seconds.toLong()).let { "%02d:%02d".format(it.hour, it.minute) }
        val now = System.currentTimeMillis()
        if (!force && (eta == etaPublished || now - etaPublishedAt < ETA_PUBLISH_MS)) return
        etaPublished = eta; etaPublishedAt = now
        scope.launch { runCatching { trips.setRouteSync(t.groupId, t.eventId, t.date, routedVersion.coerceAtLeast(0), hold = revertedVersion >= 0, etaNext = eta) } }
    }

    /** One place reached: mark it visited, then top the route up while more stops remain (Q22). */
    private fun onArrived() {
        val t = trip.value ?: return
        // The first thing in the navigation is what we reached: a pickup, a station, or a planned stop.
        val first = inNavi.firstOrNull()
        when (first?.kind) {
            NaviEntry.Kind.PICKUP -> {
                inNavi = inNavi.drop(1)
                scope.launch {
                    runCatching { pickupsRepo.markArrived(t.groupId, t.eventId, t.date, first.id) }
                    val who = t.pickups.firstOrNull { it.uid == first.id }?.name?.ifBlank { null } ?: "일행"
                    _notice.value = "픽업 장소 도착 · $who"
                    voice.say("$who 픽업 장소예요.")
                }
                return
            }
            NaviEntry.Kind.STATION -> {
                // N1: a petrol stop is not a planned stop — nothing is ticked; the fill-up record follows from the fuel level (Q44).
                inNavi = inNavi.drop(1)
                _notice.value = "${first.place.name} 도착 · 넣고 나면 기록을 남길게요"
                scope.launch { voice.say("${first.place.name}이에요. 넣고 나면 기록을 남길게요.") }
                return
            }
            else -> {}
        }
        val reached = first?.let { e -> t.stops.firstOrNull { it.id == e.id } } ?: t.next ?: return
        inNavi = inNavi.drop(1)
        scope.launch {
            val after = t.remaining.filterNot { it.id == reached.id }
            runCatching {
                trips.setVisited(t.groupId, t.eventId, t.date, reached.id, true, t.stops.indexOfFirst { it.id == after.firstOrNull()?.id }.coerceAtLeast(0))
                if (after.isEmpty()) {
                    trips.setDayStatus(t.groupId, t.eventId, t.date, DayStatus.DONE)
                    inNavi = emptyList()
                    routedPlan = emptyList()
                    _guiding.value = false
                    voice.say("오늘 일정을 모두 다녀왔어요.")
                } else if (after.size > inNavi.count { it.kind == NaviEntry.Kind.STOP }) {
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
        /** Q25: several quick edits become one change. */
        private const val CHANGE_SETTLE_MS = 5_000L
        /** 10장: how long [원래대로] stays after the navigation took a change. */
        private const val REVERT_WINDOW_MS = 10_000L
        /** Q34: P without a door signal counts as arrived after this long. */
        private const val PARK_CONFIRM_MS = 5 * 60_000L
        /** 13장 4: within this of the next planned stop = that stop. */
        private const val NEAR_METERS = 300.0
        /** Q29: range must cover the rest of the route plus this. */
        private const val ROUTE_MARGIN_KM = 30.0
        private const val LOW_FUEL_PERCENT = 20.0
        private const val LOW_RANGE_KM = 50.0
        /** Q26 */
        private const val SHARE_INTERVAL_MS = 5_000L
        /** 11장 7 */
        private const val PICKUP_DONE_MS = 10 * 60_000L
        /** A second stop within this long and [NEAR_METERS] of an unplanned record reuses it. */
        private const val SAME_PARKING_MS = 30 * 60_000L
        /** Kakao Mobility fallback for the ETA when the navigation gives no driving info: at most this often. */
        private const val KAKAO_ETA_MIN_MS = 5 * 60_000L
        private val START_REASONS = setOf(PromptReason.APP_START, PromptReason.GEAR_D, PromptReason.MANUAL, PromptReason.PICKUP)
        /** How long the command buttons stay after [말하기] did not understand. */
        private const val VOICE_CHOICES_MS = 10_000L
        /** How often the car's ETA goes to the day document at most (묶음 5). */
        private const val ETA_PUBLISH_MS = 20_000L
        /** ETA refresh while guiding (navigation driving info; Kakao only as the fallback above). */
        private const val ETA_INTERVAL_MS = 30_000L
        /** 12장: "주차장 찾아봐 주세요" this many seconds before the next stop. */
        private const val PARKING_HINT_S = 5 * 60
        /** 주행 화면 refresh (position + next turn). */
        private const val LIVE_INTERVAL_MS = 4_000L

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

        /** "충전소가" / "주유소가" / "휴게소가" — 이/가 by the last syllable's final consonant. */
        private fun ga(name: String): String {
            val last = name.lastOrNull() ?: return "가"
            if (last !in '가'..'힣') return "이(가)"
            return if ((last - '가') % 28 == 0) "가" else "이"
        }

        private fun eul(name: String): String {
            val last = name.lastOrNull() ?: return "를"
            if (last !in '가'..'힣') return "을(를)"
            return if ((last - '가') % 28 == 0) "를" else "을"
        }

        private fun ro(name: String): String = withRo(name).removePrefix(name)
    }
}
