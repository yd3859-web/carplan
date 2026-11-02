package com.htb.carplan.car

import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.constants.NaviErrorCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import android.content.Context
import android.util.Log
import com.htb.carplan.shared.model.Place

/**
 * Puts places into the Hyundai navigation. The SDK only names a first and second
 * waypoint, so at most two waypoints plus the destination are sent and refilled as
 * each one is reached (9장, Q22).
 */
class NaviRouter(private val context: Context) {
    private val navi = NaviHelper(context)
    private var ready = false
    private var pending: List<Place>? = null

    /** The navigation's own saved places (즐겨찾기 — 집·회사 first — then 최근 목적지), with coordinates. */
    private val _saved = MutableStateFlow<List<SavedPlace>>(emptyList())
    val saved: StateFlow<List<SavedPlace>> = _saved
    private var bookmarks = emptyList<SavedPlace>()
    private var recents = emptyList<SavedPlace>()

    /** The navigation's current destination; changes when the driver picks a place in the navigation itself. */
    private val _destination = MutableStateFlow<Place?>(null)
    val destination: StateFlow<Place?> = _destination

    /**
     * 내비에서 고르기: our app opens the navigation's search, the driver picks a place and starts it, we read the
     * destination back (name + coordinates), put the earlier route back (or cancel) and come to the front again.
     */
    private var picking = false
    private var pickEndedAt = 0L
    private val _picked = MutableStateFlow<Place?>(null)
    val picked: StateFlow<Place?> = _picked
    /** True while a pick runs and shortly after: the route start/cancel it causes is not a real drive. */
    val pickBusy: Boolean get() = picking || System.currentTimeMillis() - pickEndedAt < PICK_SETTLE_MS
    /** Set by TripNavigator: re-sends today's route if one was being guided; false when there was none. */
    var restoreRoute: (() -> Boolean)? = null

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** The destination before the pick (today's route, maybe): only a different one counts as picked. */
    private var pickBaseline: Place? = null
    private var baselineKnown = false
    private val pollDestination = object : Runnable {
        override fun run() { if (!picking) return; runCatching { navi.getDestinationInfo() }; main.postDelayed(this, PICK_POLL_MS) }
    }

    fun pickInNavi(keyword: String) {
        _picked.value = null; picking = true; pickBaseline = null; baselineKnown = false
        runCatching { navi.getDestinationInfo() } // the first answer is the baseline
        search(keyword)
        // The route-start event comes only after the whole route is computed (≈10 s far away): ask every second instead.
        main.removeCallbacks(pollDestination); main.postDelayed(pollDestination, PICK_POLL_MS)
    }
    fun stopPicking() { picking = false; main.removeCallbacks(pollDestination) }
    fun consumePicked() { _picked.value = null }

    private fun onPicked(place: Place) {
        stopPicking()
        pickEndedAt = System.currentTimeMillis()
        _picked.value = place
        if (restoreRoute?.invoke() != true) runCatching { navi.cancelRoute() }
        context.startActivity(android.content.Intent(context, MainActivity::class.java).addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private val listener = object : NaviHelperEventListener {
        override fun onBookmarkInfo(list: List<BookmarkInfo>) {
            Log.i(TAG, "bookmarks ${list.size}: ${list.joinToString { "${it.category}/${it.name}" }}")
            bookmarks = list.map { SavedPlace(Place(name = it.name, address = it.address, lat = it.latitude, lng = it.longitude), it.category.name) }
            _saved.value = (bookmarks + recents).distinctBy { it.place.name }
        }
        override fun onRecentDestinationInfo(list: List<RecentDestinationInfo>) {
            Log.i(TAG, "recents ${list.size}: ${list.joinToString { it.name }}")
            recents = list.map { SavedPlace(Place(name = it.name, address = it.address, lat = it.latitude, lng = it.longitude), null) }
            _saved.value = (bookmarks + recents).distinctBy { it.place.name }
        }
        // Picking a place in the navigation fires no onDestinationChanged on the emulator, only a route start: ask then.
        override fun onRouteStarted(info: ai.pleos.playground.navi.data.RouteStartInfo) { runCatching { navi.getDestinationInfo() } }
        override fun onDestinationChanged() { Log.i(TAG, "destination changed"); runCatching { navi.getDestinationInfo() } }
        override fun onDestinationInfo(info: DestinationInfo) {
            Log.i(TAG, "destination ${info.name} ${info.latitude},${info.longitude} ${info.address}")
            val p = Place(name = info.name, address = info.address, lat = info.latitude, lng = info.longitude)
            _destination.value = p
            if (picking && !baselineKnown) { baselineKnown = true; pickBaseline = p.takeIf { info.name.isNotBlank() }; return }
            val base = pickBaseline
            val isNew = base == null || kotlin.math.abs(base.lat - p.lat) > 1e-5 || kotlin.math.abs(base.lng - p.lng) > 1e-5
            if (picking && info.name.isNotBlank() && (info.latitude != 0.0 || info.longitude != 0.0) && isNew) onPicked(p)
        }
        override fun onBookMarkChanged() { requestSaved() }
        override fun onError(code: NaviErrorCode) { Log.w(TAG, "navi error $code") }
    }

    fun start() {
        navi.initialize({
            ready = true
            navi.addListener(listener)
            requestSaved()
            pending?.let { route(it) }
            pending = null
        }, { Log.e(TAG, "NaviHelper initialize failed", it) })
    }

    /** Routes through [places] in order; the last one is the destination. */
    fun route(places: List<Place>) {
        if (!ready) {
            pending = places
            return
        }
        if (places.isEmpty()) {
            navi.cancelRoute()
            return
        }
        val destination = places.last()
        val waypoints = places.dropLast(1).take(MAX_WAYPOINTS)
        if (waypoints.isEmpty()) {
            navi.requestRoute(destination.toRouteInfo())
        } else {
            navi.requestRouteWithWaypoints(
                RouteWithWaypointsRequest(
                    destination.toRouteInfo(),
                    waypoints.mapIndexed { i, p -> p.toWaypoint(if (i == 0) WaypointIndex.FIRST else WaypointIndex.SECOND) },
                ),
            )
        }
    }

    /** Opens the navigation's own search for [keyword] (places without coordinates yet). */
    fun search(keyword: String) {
        if (ready) navi.requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword))
        openNavigation()
    }

    /** Brings the navigation app to the front (requestNavigationScreen alone does not). */
    fun openNavigation() {
        context.packageManager.getLaunchIntentForPackage(NAVI_PACKAGE)?.let {
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(it)
        }
    }

    fun addListener(listener: NaviHelperEventListener) = navi.addListener(listener)

    /** Asks the navigation for the car's position; the answer arrives at onCurrentLocationInfo on the listeners. */
    fun requestCurrentLocation() { if (ready) runCatching { navi.getCurrentLocationInfo() } }

    /** Asks for the next turns (주행 화면); the answer arrives at onTBTInfo. */
    fun requestTbt() { if (ready) runCatching { navi.getTBTInfo() } }

    /** Asks for 즐겨찾기 + 최근 목적지; answers fill [saved]. */
    fun requestSaved() { if (ready) runCatching { navi.getBookmarkInfo(); navi.getRecentDestinationInfo() } }

    /** Asks for the current destination; the answer fills [destination]. */
    fun requestDestination() { if (ready) runCatching { navi.getDestinationInfo() } }

    fun release() = navi.release()

    private fun Place.toRouteInfo() =
        RouteInfo(lat, lng, null, null, "", "", name, "", RouteOption.values().first(), "", false)

    private fun Place.toWaypoint(index: WaypointIndex) =
        RequestWaypointInfo(lat, lng, "", index, "", name, "", "")

    companion object {
        private const val TAG = "NaviRouter"
        private const val MAX_WAYPOINTS = 2
        private const val PICK_SETTLE_MS = 5_000L
        private const val PICK_POLL_MS = 1_000L
        const val NAVI_PACKAGE = "ai.umos.maps.android.navigation.app"
    }
}

/** A place the navigation already knows: [kind] is the 즐겨찾기 type (HOME, OFFICE, …), null for a recent one. */
data class SavedPlace(val place: Place, val kind: String?)
