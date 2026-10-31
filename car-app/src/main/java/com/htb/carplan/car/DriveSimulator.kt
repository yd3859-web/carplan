package com.htb.carplan.car

import android.util.Log
import com.htb.carplan.shared.calendar.RouteMath
import com.htb.carplan.shared.calendar.RouteMath.hasCoords
import com.htb.carplan.shared.model.Gear
import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.net.KakaoApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** What the simulator is doing right now — the sim bar on the car screen draws this. */
data class SimState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val speedX: Int = 1,
    val phase: String = "",
    val target: String? = null,
    val remainMeters: Int? = null,
    /** True when gear/door/seat go through the Virtual Console (the app receives real car signals). */
    val viaConsole: Boolean = false,
)

/**
 * 시뮬레이션 (다음작업 0단계): plays today's trip with one button — board, P → D, drive the real
 * road (Kakao Mobility geometry) to each pickup/stop, park, open the driver's door, get out.
 * Gear, speed, doors and seats go through the emulator's Virtual Console, so the app reacts to the
 * same signals a car would send; without the console they are injected into [VehicleMonitor].
 * Only the position is fed directly: the emulator GPS cannot move.
 */
class DriveSimulator(
    private val navigator: TripNavigator,
    private val monitor: VehicleMonitor,
    private val scope: CoroutineScope,
    /** The navigation jumps in front whenever it gets a route; the sim is for watching our screen. */
    private val bringToFront: () -> Unit,
) {
    private val kakao = KakaoApi(BuildConfig.KAKAO_REST_KEY)
    private val console = VirtualConsole()
    private val _state = MutableStateFlow(SimState())
    val state: StateFlow<SimState> = _state.asStateFlow()
    private var job: Job? = null
    private var heartbeat: Job? = null
    private val routes = HashMap<String, com.htb.carplan.shared.net.Route>()

    fun play() {
        if (job?.isActive == true) return
        job = scope.launch {
            val viaConsole = console.reachable()
            _state.value = SimState(running = true, viaConsole = viaConsole, phase = "준비", speedX = _state.value.speedX)
            if (viaConsole) heartbeat = scope.launch { while (isActive) { console.heartbeat(); delay(1_000) } }
            navigator.simActive = true
            try { runDay() } finally {
                // [끝] mid-drive must not leave the car in D at speed (the next app start would be "주행 중").
                withContext(NonCancellable) { speed(0); gear(Gear.P); door("front_left", false) }
                navigator.simActive = false
                navigator.simParked()
                heartbeat?.cancel()
                _state.update { it.copy(running = false, paused = false) }
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
    fun togglePause() = _state.update { it.copy(paused = !it.paused) }
    fun toggleSpeed() = _state.update { it.copy(speedX = if (it.speedX == 1) 2 else 1) }

    private suspend fun runDay() {
        phase("타는 중")
        gear(Gear.P)
        // Start from an empty car (the emulator boots with every seat occupied).
        listOf("passenger", "rear_left", "rear_right").forEach { seat(it, false) }
        hold(1_000)
        seat("driver", true)
        hold(2_000)
        // 7장 4: a passenger sits down → "같이 타셨나요?" QR.
        seat("passenger", true)
        hold(5_000)
        while (true) {
            val t = navigator.trip.value ?: run { phase("오늘 여행이 없어요"); hold(3_000); return }
            val pickup = t.waitingPickups.firstOrNull()
            val target = pickup?.place ?: t.next?.place ?: break
            if (!target.hasCoords()) { phase("${target.name}: 좌표가 없어 건너뛰어요"); hold(3_000); navigator.simSkip(); continue }
            val name = pickup?.let { "${it.name.ifBlank { "일행" }} 픽업" } ?: target.name

            phase("출발")
            door("front_left", false)
            lock(true)
            gear(Gear.D)
            // 9장: P → D asks "다음 목적지로 갈까요?" — the driver says "응" after a moment.
            hold(4_000)
            navigator.simAnswerStart()
            showUs()

            drive(target, name, isPickup = pickup != null)

            phase("$name 도착")
            speed(0)
            gear(Gear.P)
            navigator.simParked()
            hold(2_000)
            if (pickup != null) {
                navigator.simArrivedPickup(pickup.uid)
                hold(3_000)
                phase("${pickup.name.ifBlank { "일행" }} 타는 중")
                door("rear_right", true); hold(1_500); door("rear_right", false)
                seat("rear_right", true)
                navigator.simBoarded(pickup.uid)
                hold(2_000)
            } else {
                // 13장: P + driver door = got out → record for this stop.
                lock(false)
                door("front_left", true)
                phase("$name · 내림")
                showUs() // arriving refills the route → the navigation comes forward again
                hold(6_000)
                door("front_left", false)
                if (navigator.trip.value?.next == null) break
                phase("다시 타는 중")
                hold(2_000)
            }
        }
        phase("오늘 일정 끝")
        speed(0)
        gear(Gear.P)
        lock(false)
        door("front_left", true)
        hold(1_500)
        door("front_left", false)
        lock(true)
        hold(2_000)
    }

    /** Glides along the road from the car to [target]; the screen and the phones see the position move. */
    private suspend fun drive(target: Place, name: String, isPickup: Boolean) {
        phase("$name 가는 중", target = name)
        val here = navigator.simLocation()?.takeIf { RouteMath.metersBetween(it.first, it.second, target.lat, target.lng) < FAR_METERS }
            ?: (target.lat - 0.025 to target.lng - 0.03) // no sensible start (emulator GPS): begin ~4 km away
        val from = Place(name = "차", lat = here.first, lng = here.second)
        // Same leg in the next test run (≈100 m either end) → reuse the road instead of asking Kakao again (API quota).
        val key = "%.3f,%.3f>%.3f,%.3f".format(from.lat, from.lng, target.lat, target.lng)
        val route = routes[key] ?: runCatching { kakao.route(from, target) }.onFailure { Log.w(TAG, "route: ${it.message}") }.getOrNull()?.also { routes[key] = it }
        val path = route?.path?.takeIf { it.size >= 2 } ?: listOf(here, target.lat to target.lng)
        val cum = DoubleArray(path.size)
        for (i in 1 until path.size) cum[i] = cum[i - 1] + RouteMath.metersBetween(path[i - 1].first, path[i - 1].second, path[i].first, path[i].second)
        val total = cum.last().coerceAtLeast(1.0)
        val realSeconds = route?.leg?.seconds?.toDouble() ?: (total / 1000 / 40 * 3600)
        val avgKph = (total / realSeconds * 3.6).coerceIn(25.0, 90.0)
        // Screen time for the leg at 1x: a 30-minute drive takes ~45 s.
        val screenMs = (realSeconds * 1000 / 40).coerceIn(20_000.0, 60_000.0)
        var done = 0.0
        var lastSpeedAt = 0L
        var hinted = false
        while (done < 1.0) {
            delay(TICK_MS)
            if (_state.value.paused) { speed(0); continue }
            done = (done + TICK_MS * _state.value.speedX / screenMs).coerceAtMost(1.0)
            val at = pointAt(path, cum, total * done)
            val remainM = (total * (1 - done)).toInt()
            val remainS = (realSeconds * (1 - done)).toInt()
            navigator.simMove(at.first, at.second, remainM, remainS, target.name)
            _state.update { it.copy(remainMeters = remainM) }
            val now = System.currentTimeMillis()
            if (now - lastSpeedAt >= 1_000) {
                lastSpeedAt = now
                // Slow down over the last stretch so arriving looks like arriving.
                val kph = if (done > 0.92) avgKph * (1 - done) / 0.08 else avgKph * (0.85 + 0.3 * Math.random())
                speed(kph.toInt())
                navigator.simEta(remainS, isPickup)
            }
            if (!hinted && !isPickup && remainS <= 300) { hinted = true; navigator.simParkingHint() }
        }
    }

    private fun showUs() = scope.launch { repeat(2) { delay(1_500); bringToFront() } }

    private fun pointAt(path: List<Pair<Double, Double>>, cum: DoubleArray, d: Double): Pair<Double, Double> {
        var i = cum.indexOfFirst { it >= d }.coerceAtLeast(1)
        if (i >= path.size) i = path.size - 1
        val seg = (cum[i] - cum[i - 1]).takeIf { it > 0 } ?: return path[i]
        val f = ((d - cum[i - 1]) / seg).coerceIn(0.0, 1.0)
        val a = path[i - 1]; val b = path[i]
        return (a.first + (b.first - a.first) * f) to (a.second + (b.second - a.second) * f)
    }

    private fun phase(text: String, target: String? = null) = _state.update { it.copy(phase = text, target = target, remainMeters = if (target == null) null else it.remainMeters) }

    /** Waits [ms] of sim time: halves at 2x, stands still while paused. */
    private suspend fun hold(ms: Long) {
        var left = ms
        while (left > 0) {
            delay(TICK_MS)
            if (!_state.value.paused) left -= TICK_MS * _state.value.speedX
        }
    }

    // ---- signals: Virtual Console when it is there, else straight into the monitor ----

    private suspend fun gear(g: Gear) {
        if (_state.value.viaConsole) console.put("/vehicle/gear?gear=" + if (g == Gear.D) "Drive" else "Park")
        else monitor.simGear(g)
    }

    private suspend fun door(area: String, open: Boolean) {
        val field = mapOf("front_left" to "door_fl", "front_right" to "door_fr", "rear_left" to "door_rl", "rear_right" to "door_rr").getValue(area)
        if (_state.value.viaConsole) console.put("/vehicle/body?$field=$open")
        else if (open && area == "front_left") monitor.simDriverDoorOpened()
    }

    private suspend fun seat(area: String, occupied: Boolean) {
        if (_state.value.viaConsole) console.put("/vehicle/seat?kind=occupied&value=$occupied&area=$area")
        // The emulator's seat listener barely fires (2 events in 80 s, late values): tell the app directly.
        if (occupied && area != "driver") monitor.simPassengerSeated()
    }

    private suspend fun lock(locked: Boolean) { if (_state.value.viaConsole) console.put("/vehicle/body?doors_locked=$locked") }

    private suspend fun speed(kph: Int) {
        if (_state.value.viaConsole) console.put("/vehicle/controls?speed=$kph&accelerator=${if (kph > 0) 20 else 0}&brake=${if (kph > 0) 0 else 30}&steering=0")
    }

    companion object {
        private const val TAG = "DriveSimulator"
        private const val TICK_MS = 500L
        /** Farther than this from the first stop, the car "starts" near it instead (the emulator sits in one fixed spot). */
        private const val FAR_METERS = 60_000.0
    }
}

/**
 * The emulator's Virtual Console (docs/기획정리본.html 09장), reachable from inside the emulator at
 * 127.0.0.1:8088. Writes only take effect while a client sends a heartbeat every second.
 */
private class VirtualConsole {
    suspend fun reachable(): Boolean = request("GET", "/vehicle/state") != null
    suspend fun heartbeat() { request("POST", "/browser/session?client=carplan-sim") }
    suspend fun put(path: String) { request("PUT", path) }

    private suspend fun request(method: String, path: String): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(BASE + path).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 1_500
            c.readTimeout = 2_000
            try { c.responseCode.also { c.inputStream.use { s -> s.readBytes() } } } finally { c.disconnect() }
        }.onFailure { Log.w("VirtualConsole", "$method $path: ${it.message}") }.getOrNull()
    }

    companion object { private const val BASE = "http://127.0.0.1:8088" }
}
