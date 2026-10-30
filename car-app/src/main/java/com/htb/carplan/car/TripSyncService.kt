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
import com.htb.carplan.shared.data.CarRepository
import com.htb.carplan.shared.data.ensureSignedIn
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the car app alive in the foreground: uploads the car's state (gear, fuel,
 * range, driving restriction) so phones see it, and will carry itinerary → navi sync (10장).
 */
class TripSyncService : LifecycleService() {

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        startInForeground()
        val monitor = CarRuntime.monitor(this)
        lifecycleScope.launch {
            val carId = runCatching { FirebaseAuth.getInstance().ensureSignedIn() }
                .onFailure { Log.e(TAG, "sign-in failed", it) }.getOrNull() ?: return@launch
            val cars = CarRepository()
            cars.ensureCar(carId)
            CarRuntime.navigator(this@TripSyncService).bind(carId)
            monitor.state.debounce(1_000).collect { state ->
                runCatching { cars.updateState(carId, state) }.onFailure { Log.w(TAG, "state upload failed", it) }
                CarRuntime.navigator(this@TripSyncService).onCarState(state) // 12장 fuel check
            }
        }
        // 9장: P → D asks "다음 목적지로 갈까요?" even while the navigation is in front.
        lifecycleScope.launch {
            monitor.state.map { it.gear }.distinctUntilChanged().collect { CarRuntime.navigator(this@TripSyncService).onGear(it) }
        }
        // 13장: driver door while parked = arrived.
        lifecycleScope.launch { monitor.driverDoorOpened.collect { CarRuntime.navigator(this@TripSyncService).onDriverDoorOpened() } }
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "일정 동기화", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("carplan")
            .setContentText("일정과 차 상태를 맞추는 중")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    companion object {
        private const val TAG = "TripSyncService"
        private const val CHANNEL_ID = "trip_sync"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TripSyncService::class.java))
        }
    }
}

/** Process-wide car objects shared by the service and the screen. */
object CarRuntime {
    private var monitor: VehicleMonitor? = null
    private var router: NaviRouter? = null

    @Synchronized
    fun monitor(context: Context): VehicleMonitor =
        monitor ?: VehicleMonitor(context.applicationContext).also { it.start(); monitor = it }

    @Synchronized
    fun router(context: Context): NaviRouter =
        router ?: NaviRouter(context.applicationContext).also { it.start(); router = it }

    private var voice: VoicePrompt? = null
    private var navigator: TripNavigator? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Synchronized
    fun voice(context: Context): VoicePrompt =
        voice ?: VoicePrompt(context.applicationContext).also { it.start(); voice = it }

    /** 9장 "다음 목적지로 갈까요?" — one per process, shared by the service and the screen. */
    @Synchronized
    fun navigator(context: Context): TripNavigator =
        navigator ?: TripNavigator(router(context), voice(context), scope).also { navigator = it }
}
