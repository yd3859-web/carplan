package com.htb.carplan.car

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.htb.carplan.shared.data.DevTrip
import com.htb.carplan.shared.data.TripRepository
import com.htb.carplan.shared.model.Stop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.LocalDate

/**
 * Stays alive in the foreground so itinerary changes reach the navigation
 * even while the navigation app is in front (10장).
 */
class TripSyncService : LifecycleService() {

    private lateinit var router: NaviRouter

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        router = NaviRouter(this).also { it.start() }
        lifecycleScope.launch {
            // TODO(1장): replace anonymous sign-in with the linked owner account (7장).
            val auth = FirebaseAuth.getInstance()
            if (auth.currentUser == null) auth.signInAnonymously().await()
            val today = LocalDate.now().toString()
            TripRepository()
                .observeStops(DevTrip.GROUP_ID, DevTrip.TRIP_ID, today)
                .distinctUntilChanged()
                .catch { Log.e(TAG, "stop sync failed", it) }
                .collect { stops ->
                    _todayStops.value = stops
                    // TODO(10장, Q23): while driving, ask by voice before rerouting.
                    router.applyRoute(stops.filterNot { it.visited })
                }
        }
    }

    override fun onDestroy() {
        router.release()
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "일정 동기화", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("carplan")
            .setContentText("오늘 일정을 내비와 맞추는 중")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    companion object {
        private const val TAG = "TripSyncService"
        private const val CHANNEL_ID = "trip_sync"
        private const val NOTIFICATION_ID = 1

        private val _todayStops = MutableStateFlow<List<Stop>>(emptyList())
        val todayStops: StateFlow<List<Stop>> = _todayStops.asStateFlow()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TripSyncService::class.java))
        }
    }
}
