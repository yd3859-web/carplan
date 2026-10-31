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
    /** Pickup uids currently in the navigation route (in front of [routed] stops). */
    private var routedPickups: List<String> = emptyList()
    private var fuelAtPark: Double? = null
    /** The station the driver picked (12장), so the fill-up record says where and at what price. */
    private var pickedStation: GasStation? = null
    private var fueledAsked = false
    /** 12장 설정: cars/{id}.fuelAlertPercent (default 20); the range threshold scales with it (20% → 50 km). */
    private var fuelAlertPercent = LOW_FUEL_PERCENT

    private val carId = MutableStateFlow<String?>(null)
    private val _prompt = MutableStateFlow<NextPrompt?>(null)
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
            }
            override fun onRouteCancelled() {
                // Our own re-request cancels the previous route first; only a cancel we did not cause ends guiding.
                if (System.currentTimeMillis() - lastRouteSentAt < OWN_CANCEL_WINDOW_MS) return
                Log.i(TAG, "route cancelled by navigation")
                routed = emptyList()
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
                if (!askedOnStart && t.next != null) { askedOnStart = true; ask(PromptReason.APP_START) }
                // 10장: the plan changed under an active route → wait 5 s for more edits, then compare.
                if (_guiding.value && routedVersion >= 0 && t.version != routedVersion && t.version != heldVersion) scheduleChangeCheck()
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

    /** Gear events from [VehicleMonitor]: P → D asks "다음 목적지로 갈까요?"; D → P re-asks a held change (Q24). */
    fun onGear(gear: Gear) {
        val prev = lastGear
        lastGear = gear
        if (prev == Gear.P && gear == Gear.D && !_guiding.value && trip.value?.next != null) ask(PromptReason.GEAR_D)
        if (prev == Gear.D && gear == Gear.P && pendingChange) { heldVersion = -1; scheduleChangeCheck(delayMs = 0) }
        // 13장: P starts the 5-minute fallback; leaving P closes the record and resets.
        if (gear == Gear.P && prev != Gear.P) {
            // Parked = arrived: "근처 주차장 찾아볼까요?" is no longer a question.
            if (_prompt.value?.reason == PromptReason.PARKING) { askJob?.cancel(); _prompt.value = null }
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
                    val id = records.addRecord(t.groupId, t.eventId, today, Record(place = next.place, stopId = next.id, createdBy = carUid))
                    parkedRecord = Triple(t.groupId, t.eventId, id); parkedDate = today
                    _notice.value = "기록 칸: ${next.place.name} ($reason)"
                    onArrived() // ✓ + refill route
                } else {
                    // 13장: "건천휴게소" rather than the navigation's street address, when Kakao knows the spot.
                    val known = loc?.let { (la, lo) -> runCatching { kakao.nearestPlace(la, lo) }.getOrNull() }
                    val place = Place(name = known?.name ?: lastAddress.ifBlank { "이름 없는 곳" }, lat = loc?.first ?: 0.0, lng = loc?.second ?: 0.0, address = known?.address?.ifBlank { null } ?: lastAddress)
                    val (g, e) = if (t != null) t.groupId to t.eventId else dailyLogTarget(today) ?: return@runCatching
                    // Stopping again where we just stopped (traffic, a second look) is the same record, not a new one.
                    val again = records.recentUnplannedNear(g, e, today, place.lat, place.lng, NEAR_METERS, SAME_PARKING_MS)
                    if (again != null) { parkedRecord = Triple(g, e, again.id); parkedDate = today; return@runCatching }
                    val id = records.addRecord(g, e, today, Record(place = place, unplanned = true, createdBy = carUid))
                    parkedRecord = Triple(g, e, id); parkedDate = today
                    _notice.value = "계획 밖 기록 칸: ${place.name} ($reason)"
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

    /** 16장: a commute is due now. Asked once per day unless there is a trip (9장 asks first). */
    fun offerCommute(e: Event) {
        commuteEvent = e
        val today = LocalDate.now().toString()
        if (commuteAskedDate == today || commuteDeclinedDate == today) return
        if (trip.value?.next != null || _guiding.value || _prompt.value != null) return
        val place = e.place ?: return
        commuteAskedDate = today
        val message = "${withRo(place.name)} 갈까요?"
        askJob?.cancel()
        _prompt.value = NextPrompt(Stop(place = place), PromptReason.COMMUTE, message = message)
        askJob = scope.launch {
            val answer = voice.ask(message) { listening, heard -> _prompt.update { it?.copy(listening = listening, heard = heard ?: it.heard) } }
            when (answer) {
                VoicePrompt.Answer.YES -> go()
                VoicePrompt.Answer.NO -> later()
                VoicePrompt.Answer.UNKNOWN -> _prompt.update { it?.copy(listening = false) }
            }
        }
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
        _prompt.value?.let { p -> if (p.reason == PromptReason.FUEL && fuel >= (fuelAskedPercent ?: 0.0) + 5) { askJob?.cancel(); _prompt.value = null } }
        // 15장 Q44: parked and the tank/battery went up ≥ 5 points → "주유했어요?" once per parking.
        if (lastGear == Gear.P) {
            val base = fuelAtPark?.let { minOf(it, fuel) }?.also { fuelAtPark = it } ?: fuel.also { fuelAtPark = it }
            if (!fueledAsked && fuel >= base + 5 && _prompt.value == null) { fueledAsked = true; askFueled(state.isEv == true) }
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
        if (_prompt.value != null) return
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
        val message = "$why. ${what} 들를까요?"
        askJob?.cancel()
        _prompt.value = NextPrompt(trip.value?.next ?: Stop(), PromptReason.FUEL, message = message)
        askJob = scope.launch {
            val answer = voice.ask(message) { listening, heard -> _prompt.update { it?.copy(listening = listening, heard = heard ?: it.heard) } }
            when (answer) {
                VoicePrompt.Answer.YES -> go()
                VoicePrompt.Answer.NO -> later()
                VoicePrompt.Answer.UNKNOWN -> _prompt.update { it?.copy(listening = false) }
            }
        }
    }

    private fun askFueled(ev: Boolean) {
        val message = if (ev) "충전했어요? 충전 기록을 남길까요?" else "주유했어요? 주유 기록을 남길까요?"
        askJob?.cancel()
        _prompt.value = NextPrompt(trip.value?.next ?: Stop(), PromptReason.FUELED, message = message)
        askJob = scope.launch {
            val answer = voice.ask(message) { listening, heard -> _prompt.update { it?.copy(listening = listening, heard = heard ?: it.heard) } }
            when (answer) {
                VoicePrompt.Answer.YES -> go()
                VoicePrompt.Answer.NO -> later()
                VoicePrompt.Answer.UNKNOWN -> _prompt.update { it?.copy(listening = false) }
            }
        }
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
        askJob?.cancel()
        _prompt.value = NextPrompt(t.next ?: Stop(place = t.waitingPickups.first().place), PromptReason.PICKUP, message = message)
        askJob = scope.launch {
            val answer = voice.ask(message) { listening, heard -> _prompt.update { it?.copy(listening = listening, heard = heard ?: it.heard) } }
            when (answer) {
                VoicePrompt.Answer.YES -> go()
                VoicePrompt.Answer.NO -> later()
                VoicePrompt.Answer.UNKNOWN -> _prompt.update { it?.copy(listening = false) }
            }
        }
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
        val rest = trip.value?.remaining?.map { it.place }.orEmpty().take(MAX_PLACES - 1)
        router.route(listOf(st.place) + rest)
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
        routedPickups = routedPickups.filterNot { it == uid }
        scope.launch {
            runCatching { pickupsRepo.markArrived(t.groupId, t.eventId, t.date, uid) }
            _notice.value = "픽업 장소 도착 · ${t.pickups.firstOrNull { it.uid == uid }?.name?.ifBlank { null } ?: "일행"}"
        }
    }

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

    /** 12장: five minutes out — passengers get a push; the car offers the navigation's own parking search. */
    private fun askParking(next: Stop) {
        if (_prompt.value != null) return
        val message = "곧 ${next.place.name} 도착이에요. 근처 주차장 찾아볼까요?"
        _prompt.value = NextPrompt(next, PromptReason.PARKING, message = message)
        askJob = scope.launch {
            when (voice.ask(message) { listening, heard -> _prompt.update { it?.copy(listening = listening, heard = heard ?: it.heard) } }) {
                VoicePrompt.Answer.YES -> go()
                VoicePrompt.Answer.NO -> later()
                VoicePrompt.Answer.UNKNOWN -> _prompt.update { it?.copy(listening = false) }
            }
        }
    }

    /** "응" / [가기] (9장) or [바꾸기] (10장): route the remaining stops and open the navigation. */
    fun go() {
        if (_prompt.value?.reason == PromptReason.PARKING) {
            askJob?.cancel(); _prompt.value = null; router.search("주차장"); return
        }
        if (_prompt.value?.reason == PromptReason.FUEL) {
            askJob?.cancel(); _prompt.value = null; findStation(); return
        }
        if (_prompt.value?.reason == PromptReason.FUELED) {
            askJob?.cancel(); _prompt.value = null; logFuel(); return
        }
        if (_prompt.value?.reason == PromptReason.COMMUTE) {
            askJob?.cancel(); _prompt.value = null
            commuteEvent?.place?.let { p -> if (p.hasCoords()) { router.route(listOf(p)); router.openNavigation() } else router.search(p.name) }
            lastRouteSentAt = System.currentTimeMillis()
            _notice.value = "${commuteEvent?.title ?: "출근"} · ${commuteEvent?.place?.name} 안내"
            return
        }
        val wasPickup = _prompt.value?.reason == PromptReason.PICKUP
        val t = trip.value ?: return
        if (wasPickup) startSharing(t)
        askJob?.cancel()
        val wasChange = _prompt.value?.reason == PromptReason.ROUTE_CHANGE
        _prompt.value = null
        pendingChange = false
        heldVersion = -1
        routeWithPickups(t)
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
        if (_prompt.value?.reason == PromptReason.FUEL) { fuelDeclinedForRoute = true; _prompt.value = null; return }
        if (_prompt.value?.reason == PromptReason.FUELED || _prompt.value?.reason == PromptReason.PARKING) { _prompt.value = null; return }
        if (_prompt.value?.reason == PromptReason.COMMUTE) { commuteDeclinedDate = LocalDate.now().toString(); _prompt.value = null; return }
        if (_prompt.value?.reason == PromptReason.PICKUP) {
            // "아니": still drive to the pickups, just without sharing the position.
            _prompt.value = null
            trip.value?.let { routeWithPickups(it); scope.launch { runCatching { trips.setDayStatus(it.groupId, it.eventId, it.date, DayStatus.DRIVING) } } }
            return
        }
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
                    val pickup = routedPickups.firstOrNull()?.let { uid -> t.pickups.firstOrNull { it.uid == uid && it.status == PickupStatus.WAITING && it.place.hasCoords() } }
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
        routedPickups = pick.map { it.uid }
        if (batch.isEmpty() && pick.isEmpty()) {
            routed = emptyList()
            _guiding.value = false
            return
        }
        val withCoords = batch.filter { it.place.hasCoords() }
        if (withCoords.isEmpty() && pick.isEmpty()) {
            // Name-only stops (no Kakao key yet): hand the name to the navigation's own search.
            router.search(batch.first().place.name)
            routed = listOf(batch.first().id)
        } else {
            // 11장 3: pickups ride in front of the planned stops.
            router.route(pick.map { it.place } + withCoords.map { it.place })
            routed = withCoords.map { it.id }
            // While simulating, keep our screen in front (the route still goes to the navigation).
            if (!simActive) router.openNavigation()
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
        // A pickup place comes before the stops: mark the car there and keep the rest of the route.
        routedPickups.firstOrNull()?.let { uid ->
            routedPickups = routedPickups.drop(1)
            scope.launch {
                runCatching { pickupsRepo.markArrived(t.groupId, t.eventId, t.date, uid) }
                _notice.value = "픽업 장소 도착 · ${t.pickups.firstOrNull { it.uid == uid }?.name ?: "일행"}"
            }
            return
        }
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

        private fun eul(name: String): String {
            val last = name.lastOrNull() ?: return "를"
            if (last !in '가'..'힣') return "을(를)"
            return if ((last - '가') % 28 == 0) "를" else "을"
        }

        private fun ro(name: String): String = withRo(name).removePrefix(name)
    }
}
