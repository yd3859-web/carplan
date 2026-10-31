package com.htb.carplan.car

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.htb.carplan.shared.data.CalendarEvent
import com.htb.carplan.shared.calendar.Commute
import com.htb.carplan.shared.calendar.Repeat
import com.htb.carplan.shared.data.CalendarRepository
import com.htb.carplan.shared.data.CarRepository
import com.htb.carplan.shared.data.GroupRepository
import com.htb.carplan.shared.data.DartRepository
import com.htb.carplan.shared.data.Regions
import com.htb.carplan.shared.data.PickupRepository
import com.htb.carplan.shared.data.TripRepository
import com.htb.carplan.shared.model.Dart
import com.htb.carplan.shared.model.DartScope
import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.model.Group
import kotlinx.coroutines.flow.combine
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
import kotlinx.coroutines.flow.first
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
    private val navigator = CarRuntime.navigator(app)
    private val groups = GroupRepository()

    /** 9장: today's trip, the question on screen, and whether the navigation already has the route. */
    val trip: StateFlow<TodayTrip?> = navigator.trip
    val prompt: StateFlow<NextPrompt?> = navigator.prompt
    val guiding: StateFlow<Boolean> = navigator.guiding
    val notice: StateFlow<String?> = navigator.notice
    /** 주행 화면 data (next turn, remaining, position). */
    val live: StateFlow<NaviLive> = navigator.live
    /** The newest comment on the next stop ("여기 주차 어렵대") — shown under "다음" on the car. */
    val nextComment: StateFlow<Pair<String, String>?> = trip.flatMapLatest { t ->
        if (t == null) flowOf(null)
        else com.htb.carplan.shared.data.TripRepository().observeDayComments(t.groupId, t.eventId, t.date)
            .map { list -> t.next?.id?.let { id -> list.filter { it.stopId == id }.maxByOrNull { it.createdAt?.toDate()?.time ?: 0L } } }
            .map { c -> c?.let { (commenterNames[it.uid] ?: loadName(it.uid)) to it.text } }
    }.catch { emit(null) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val commenterNames = HashMap<String, String>()
    private suspend fun loadName(uid: String): String =
        runCatching { GroupRepository().user(uid)?.name }.getOrNull()?.ifBlank { null }?.also { commenterNames[uid] = it } ?: "일행"

    /** 시연 메뉴: draw the half-width (next to the navigation) layout even on the full app area. */
    val previewSplit = MutableStateFlow(false)
    /** 시연 메뉴: night colours (CarTheme) without waiting for the car to dim. */
    val previewDark = MutableStateFlow(false)
    val stations = navigator.stations
    private val monitor = CarRuntime.monitor(app)

    fun goViaStation(st: com.htb.carplan.shared.net.GasStation) = navigator.goViaStation(st)
    fun dismissStations() = navigator.dismissStations()

    // 6장 다트 (주차 중): same room as the phones
    private val dartRepo = DartRepository()
    val dart: StateFlow<Dart?> = trip.flatMapLatest { t -> t?.let { dartRepo.observeLatest(it.groupId) } ?: flowOf(null) }
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var regionsLoaded = false
    /** 6장 Q14: the full 읍·면·동 list (assets/regions.csv, 3,559 rows) replaces the built-in starter list. */
    private fun loadRegions() {
        if (regionsLoaded) return
        regionsLoaded = true
        runCatching { getApplication<Application>().assets.open("regions.csv").bufferedReader().readLines() }.onSuccess { Regions.load(it) }
    }

    fun throwDart(scope: DartScope) {
        val t = trip.value ?: return
        val u = owner.value ?: return
        loadRegions()
        viewModelScope.launch {
            val cur = dart.value
            val same = cur != null && cur.scope == scope && cur.eventId == t.eventId
            val here = vehicle.value.let { s -> if (s.lat != null && s.lng != null) s.lat to s.lng else null }
            val pick = Regions.pick(scope, here?.first, here?.second) ?: return@launch
            runCatching { dartRepo.throwDart(t.groupId, if (same) cur!!.id else null, scope, here?.first, here?.second, null, pick, if (same) cur!!.throwCount + 1 else 1, u.uid, t.eventId, t.date) }
                .onFailure { _error.value = it.message }
        }
    }

    /** 6장 game: the dart stuck at (lat, lng) → nearest 동네 in the scope (same as the phone). */
    fun throwDartAt(scope: DartScope, centerLat: Double?, centerLng: Double?, region: String?, lat: Double, lng: Double) {
        val t = trip.value ?: return
        val u = owner.value ?: return
        loadRegions()
        viewModelScope.launch {
            val cur = dart.value
            val same = cur != null && cur.scope == scope && cur.region == region && cur.eventId == t.eventId
            val pick = Regions.nearest(lat, lng, Regions.pool(scope, centerLat, centerLng, region)) ?: run { _error.value = "그 범위엔 동네가 없어요"; return@launch }
            runCatching { dartRepo.throwDart(t.groupId, if (same) cur!!.id else null, scope, centerLat, centerLng, region, pick, if (same) cur!!.throwCount + 1 else 1, u.uid, t.eventId, t.date) }
                .onFailure { _error.value = it.message }
        }
    }
    fun ensureRegions() = loadRegions()
    val ownerUid: String? get() = owner.value?.uid
    /** Owner name of a uid for "○○님이 던짐" (names are in users/{uid}; the car only knows its owner). */
    fun nameOf(uid: String): String = if (uid == owner.value?.uid) owner.value?.name?.ifBlank { null } ?: "차주" else "일행"

    fun addDartAsStop() {
        val t = trip.value ?: return
        val d = dart.value ?: return
        val u = owner.value ?: return
        viewModelScope.launch {
            runCatching { val parts = d.resultName.split(" "); TripRepository().addStop(t.groupId, t.eventId, t.date, Place(name = parts.last(), lat = d.resultLat, lng = d.resultLng, address = d.resultName), null, "다트 · " + parts.dropLast(1).drop(1).joinToString(" "), u.uid) }
                .onFailure { _error.value = it.message }
        }
    }

    // 20장 디버그 메뉴
    fun setFuelOverride(percent: Double?) { monitor.fuelOverride = percent }
    fun setEvOverride(ev: Boolean?) { monitor.evOverride = ev }
    private val simulator = CarRuntime.simulator(app)
    val sim: StateFlow<SimState> = simulator.state
    fun playSim() = simulator.play()
    fun stopSim() = simulator.stop()
    fun pauseSim() = simulator.togglePause()
    fun speedSim() = simulator.toggleSpeed()

    private val carId = MutableStateFlow<String?>(null)
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val vehicle: StateFlow<CarState> = CarRuntime.monitor(app).state
    /** 7장 4: a passenger sat down → the screen offers the QR by itself. */
    val passengerSeated = CarRuntime.monitor(app).passengerSeated
    val passengerCount: StateFlow<Int> = CarRuntime.monitor(app).passengerCount

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

    /** 17장 표: 차량 관리 시기는 차 첫 화면에도 — this week's nearest 차량 관리 due (events `due-*`, 15장). */
    val careDue: StateFlow<String?> = week.map { all ->
        val today = LocalDate.now()
        all.filter { it.event.categoryId == com.htb.carplan.shared.model.BuiltInCategory.CAR.id && it.event.id.startsWith("due-") && LocalDate.parse(it.event.startDate) >= today }
            .minByOrNull { it.event.startDate }
            ?.let { e ->
                val days = java.time.temporal.ChronoUnit.DAYS.between(today, LocalDate.parse(e.event.startDate))
                val what = e.event.maintenance?.item?.label ?: e.event.title
                what + " " + if (days == 0L) "오늘" else "${days}일 뒤"
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        TripSyncService.start(app)
        // 16장 Q46: within ±60 min of a registered commute, offer it (the trip question wins when there is a trip).
        viewModelScope.launch {
            week.collect { all ->
                val c = Commute.candidate(all.map { it.event }, LocalDate.now(), LocalTime.now()) ?: return@collect
                navigator.offerCommute(c)
            }
        }
        viewModelScope.launch {
            runCatching {
                val uid = FirebaseAuth.getInstance().ensureSignedIn()
                cars.ensureCar(uid)
                uid
            }.onSuccess { carId.value = it }.onFailure { _error.value = it.message }
        }
    }

    fun eventsOn(date: LocalDate, all: List<CalendarEvent>): List<CalendarEvent> = all.filter { Repeat.occursOn(it.event, date) }
        .sortedWith(compareBy({ !it.event.allDay }, { it.event.startTime ?: "" }))

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
    /** Ask the navigation where the car is (answer lands in [live] / [lastLocation]). */
    fun requestLocation() = router.requestCurrentLocation()
    fun lastLocation(): Pair<Double, Double>? = navigator.simLocation()

    /** 시연 메뉴: a passenger waiting near Pangyo for today's trip (11장) — the simulation picks them up first. */
    fun makeTestPickup() {
        val t = trip.value ?: return
        viewModelScope.launch {
            runCatching { PickupRepository().setMine(t.groupId, t.eventId, t.date, TEST_PICKUP_UID, "시험 일행", Place(name = "백현동 카페거리", lat = 37.3870, lng = 127.1180, address = "성남시 분당구 백현동")) }
                .onFailure { _error.value = it.message }
        }
    }

    /** 7장 3: the group the QR invites to — today's trip's group, or the one made with [새 그룹] (Q16). */
    private val qrGroupId = MutableStateFlow<String?>(null)
    val qrGroup: StateFlow<Group?> = combine(qrGroupId, trip) { chosen, t -> chosen ?: t?.groupId }
        .flatMapLatest { id -> id?.let { groups.observeGroup(it) } ?: flowOf(null) }
        .map { g -> g?.takeIf { !it.solo } } // a solo "나만" group is not something to invite people into
        .catch { _error.value = it.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 초대 = 일정 단위 (A안): today's trip still in the owner's solo group gets its own group (moved with its days,
     * stops and records) and the QR shows right away. The car follows the trip to its new place.
     */
    fun inviteToTodayTrip() {
        val t = trip.value ?: return
        val id = carId.value
        viewModelScope.launch {
            runCatching {
                val gid = groups.shareEvent(t.groupId, t.eventId)
                qrGroupId.value = gid
                if (id != null && car.value?.activeEventId == t.eventId) cars.setActiveTrip(id, gid, t.eventId)
            }.onFailure { _error.value = it.message }
        }
    }

    /** Q16: no trip today → make a group on the spot (owned by the car's host) and show its QR. */
    fun newGroupForQr() {
        val u = owner.value ?: return
        viewModelScope.launch {
            val today = LocalDate.now()
            runCatching { groups.createGroup("${today.monthValue}/${today.dayOfMonth} 같이 탄 사람들", u.uid) }
                .onSuccess { qrGroupId.value = it }
                .onFailure { _error.value = it.message }
        }
    }

    fun clearNotice() = navigator.clearNotice()
    fun go() = navigator.go()
    fun later() = navigator.later()
    fun askNow() = navigator.askNow()
    val tripsToday = navigator.tripsToday
    fun chooseTrip(groupId: String, eventId: String) = navigator.chooseTrip(groupId, eventId)
    val voiceState: StateFlow<VoiceState> = navigator.voiceState
    fun listen() = navigator.listenForCommand()
    fun runCommand(c: VoiceCommand) = navigator.runCommand(c)
    fun closeVoice() = navigator.closeVoice()
    fun debugArrive() = navigator.debugArrive()

    /** 차에서 일정 만들기 (Q19), parked only. Goes to the owner's solo group. */
    /** 시연 메뉴: a fresh trip today (or the same one rewound) (3 stops around Pangyo, this car) so the simulation always has something to play. */
    fun makeTestTrip() {
        val u = owner.value ?: return
        val id = carId.value ?: return
        val today = LocalDate.now().toString()
        // Played already today: rewind it instead of piling up another trip (the car would keep showing the finished one).
        trip.value?.takeIf { it.title == "시험 여행" }?.let { t ->
            viewModelScope.launch {
                runCatching {
                    val trips = TripRepository()
                    t.stops.filter { it.visited }.forEach { trips.setVisited(t.groupId, t.eventId, t.date, it.id, false, 0) }
                    // Stops taken out with [칸 하나 빼기] come back too (the trip's own list hides deleted ones).
                    trips.observeStops(t.groupId, t.eventId, t.date).first().filter { it.deleted }
                        .forEach { trips.setDeleted(t.groupId, t.eventId, t.date, it.id, false, owner.value?.uid.orEmpty()) }
                    trips.setDayStatus(t.groupId, t.eventId, t.date, com.htb.carplan.shared.model.DayStatus.READY)
                    runCatching { PickupRepository().remove(t.groupId, t.eventId, t.date, TEST_PICKUP_UID) }
                }.onFailure { _error.value = it.message }
            }
            return
        }
        viewModelScope.launch {
            runCatching {
                val eventId = calendar.addEvent(u.soloGroupId, Event(categoryId = com.htb.carplan.shared.model.BuiltInCategory.TRIP.id, title = "시험 여행", startDate = today, endDate = today, carId = id, createdBy = u.uid))
                val trips = TripRepository()
                listOf(
                    Triple(Place(name = "판교역", lat = 37.3948, lng = 127.1112), "10:00", "여기서 출발"),
                    Triple(Place(name = "율동공원", lat = 37.3802, lng = 127.1497), "11:00", "산책"),
                    Triple(Place(name = "정자역", lat = 37.3670, lng = 127.1085), "12:30", "점심"),
                ).forEach { (place, time, memo) -> trips.addStop(u.soloGroupId, eventId, today, place, time, memo, u.uid) }
            }.onFailure { _error.value = it.message }
        }
    }

    /**
     * 시연 메뉴: a commute that is due right now (16장 "회사로 갈까요?" needs a repeating event with a place within
     * ±60 min). Every day, starting now, to "회사" in Pangyo. Pressing again replaces the previous test commute.
     */
    fun makeTestCommute() {
        val u = owner.value ?: return
        viewModelScope.launch {
            runCatching {
                week.value.filter { it.event.memo == TEST_COMMUTE_MEMO }.forEach { calendar.setEventDeleted(it.groupId, it.event.id, true) }
                val now = java.time.LocalTime.now()
                val today = LocalDate.now().toString()
                calendar.addEvent(u.soloGroupId, Event(
                    title = "출근", memo = TEST_COMMUTE_MEMO, startDate = today, endDate = today, allDay = false,
                    startTime = "%02d:%02d".format(now.hour, now.minute), repeat = "DAILY",
                    place = Place(name = "회사", lat = 37.4012, lng = 127.1086, address = "판교 테크노밸리"), createdBy = u.uid,
                ))
            }.onFailure { _error.value = it.message }
        }
    }

    /** 시연 메뉴: a second trip today (⑩ — the car follows one, [다른 여행] switches). Made once. */
    fun makeSecondTestTrip() {
        val u = owner.value ?: return
        if (navigator.tripsToday.value.any { it.event.title == "시험 여행 2" }) return
        val today = LocalDate.now().toString()
        viewModelScope.launch {
            runCatching {
                val eventId = calendar.addEvent(u.soloGroupId, Event(categoryId = com.htb.carplan.shared.model.BuiltInCategory.TRIP.id, title = "시험 여행 2", startDate = today, endDate = today, createdBy = u.uid))
                val trips = TripRepository()
                trips.addStop(u.soloGroupId, eventId, today, Place(name = "서현역", lat = 37.3850, lng = 127.1235), "14:00", "", u.uid)
                trips.addStop(u.soloGroupId, eventId, today, Place(name = "중앙공원", lat = 37.3726, lng = 127.1302), "15:00", "", u.uid)
            }.onFailure { _error.value = it.message }
        }
    }

    /** 시뮬 줄 [칸 하나 빼기]: what a passenger's phone would do (10장) — takes out the last stop still ahead. */
    fun testRemoveStop() {
        val t = trip.value ?: return
        val u = owner.value ?: return
        val target = t.remaining.drop(1).lastOrNull() ?: return
        viewModelScope.launch {
            runCatching { TripRepository().setDeleted(t.groupId, t.eventId, t.date, target.id, true, u.uid) }.onFailure { _error.value = it.message }
        }
    }

    fun addEvent(event: Event) {
        val u = owner.value ?: return
        viewModelScope.launch {
            runCatching { calendar.addEvent(u.soloGroupId, event.copy(createdBy = u.uid)) }
                .onSuccess { if (event.place != null) navigator.notify("${event.title} 추가 · 장소는 갈 때 내비 검색으로 찾아요") }
                .onFailure { _error.value = it.message }
        }
    }

    companion object {
        /** Marks the 시연 메뉴 test commute so the next press can replace it. */
        private const val TEST_COMMUTE_MEMO = "시연 메뉴 시험 출근"
        private const val TEST_PICKUP_UID = "test-passenger"
    }
}
