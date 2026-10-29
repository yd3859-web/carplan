package com.htb.carplan.car

import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.helper.NaviHelper
import android.content.Context
import android.util.Log
import com.htb.carplan.shared.model.Stop

/**
 * Puts the remaining stops of the day into the Hyundai navigation.
 * The SDK only names a first and second waypoint, so we send at most two waypoints
 * plus the destination and refill as each one is reached (9장, Q22).
 */
class NaviRouter(context: Context) {
    private val navi = NaviHelper(context)
    private var ready = false
    private var pending: List<Stop>? = null

    fun start() {
        navi.initialize({
            ready = true
            pending?.let { applyRoute(it) }
            pending = null
        }, { Log.e(TAG, "NaviHelper initialize failed", it) })
    }

    fun applyRoute(stops: List<Stop>) {
        if (!ready) {
            pending = stops
            return
        }
        if (stops.isEmpty()) {
            navi.cancelRoute()
            return
        }
        val destination = stops.last()
        val waypoints = stops.dropLast(1).take(MAX_WAYPOINTS)
        if (waypoints.isEmpty()) {
            navi.requestRoute(destination.toRouteInfo())
        } else {
            navi.requestRouteWithWaypoints(
                RouteWithWaypointsRequest(
                    destination.toRouteInfo(),
                    waypoints.mapIndexed { i, stop ->
                        stop.toWaypoint(if (i == 0) WaypointIndex.FIRST else WaypointIndex.SECOND)
                    },
                ),
            )
        }
    }

    fun release() = navi.release()

    private fun Stop.toRouteInfo() =
        RouteInfo(place.lat, place.lng, null, null, "", "", place.name, "", RouteOption.values().first(), "", false)

    private fun Stop.toWaypoint(index: WaypointIndex) =
        RequestWaypointInfo(place.lat, place.lng, "", index, "", place.name, "", "")

    companion object {
        private const val TAG = "NaviRouter"
        private const val MAX_WAYPOINTS = 2
    }
}
