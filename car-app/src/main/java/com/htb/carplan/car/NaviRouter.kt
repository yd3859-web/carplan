package com.htb.carplan.car

import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
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

    fun start() {
        navi.initialize({
            ready = true
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

    fun release() = navi.release()

    private fun Place.toRouteInfo() =
        RouteInfo(lat, lng, null, null, "", "", name, "", RouteOption.values().first(), "", false)

    private fun Place.toWaypoint(index: WaypointIndex) =
        RequestWaypointInfo(lat, lng, "", index, "", name, "", "")

    companion object {
        private const val TAG = "NaviRouter"
        private const val MAX_WAYPOINTS = 2
        const val NAVI_PACKAGE = "ai.umos.maps.android.navigation.app"
    }
}
