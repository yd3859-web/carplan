package com.htb.carplan.phone.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.htb.carplan.phone.MainActivity
import com.htb.carplan.shared.data.CalendarRepository
import com.htb.carplan.shared.data.PickupRepository
import com.htb.carplan.shared.data.SessionRepository
import com.htb.carplan.shared.data.TripRepository
import com.htb.carplan.shared.model.BuiltInCategory
import com.htb.carplan.shared.model.DayStatus
import com.htb.carplan.shared.model.PickupStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * P-e "데리러 오는 중" (애플 지도 도착 예정 공유 참고): while the car comes to pick me up with the driver's sharing on
 * (정원 2026-11-09 — never otherwise), my phone keeps one quiet notification up — "차가 약 7분 뒤 데리러 와요". The
 * numbers are the car's own (days/{date}.etaNext, pickups.etaSeconds). The driver's phone shows nothing. Lives as long
 * as the app process (no service of its own); pushes from Functions cover the rest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object TripLive {
    private const val CHANNEL = "carplan_live"
    private const val ID = 7001
    private var started = false

    private data class Live(val groupId: String, val eventId: String, val date: String, val title: String, val text: String)

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val calendar = CalendarRepository()
        val trips = TripRepository()
        val pickups = PickupRepository()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            val me = runCatching { SessionRepository().ensureSession().uid }.getOrNull() ?: return@launch
            val today = LocalDate.now().toString()
            calendar.observeGroups(me)
                .flatMapLatest { gs -> if (gs.isEmpty()) flowOf(emptyList()) else calendar.observeEvents(gs.map { it.id }, today, today) }
                .flatMapLatest { events ->
                    val tripsToday = events.filter { it.event.categoryId == BuiltInCategory.TRIP.id && !it.event.deleted }
                    if (tripsToday.isEmpty()) flowOf(null)
                    else combine(tripsToday.map { ce ->
                        val g = ce.groupId; val e = ce.event.id
                        combine(trips.observeDay(g, e, today), pickups.observe(g, e, today)) { day, ps ->
                            // The driver (or, before "오늘 이 차로 가요", the host who made it) does not need this.
                            if (day?.status != DayStatus.DRIVING || (ce.event.driverUid ?: ce.event.createdBy) == me) return@combine null
                            // 정원 2026-11-09: only while the car comes for me and the driver turned sharing on.
                            val mine = ps.firstOrNull { it.uid == me && it.status == PickupStatus.WAITING } ?: return@combine null
                            if (day.pickupSharing != true) return@combine null
                            val text = mine.etaSeconds?.let { "차가 약 ${(it / 60).coerceAtLeast(1)}분 뒤 데리러 와요" } ?: "차가 데리러 오는 중이에요"
                            Live(g, e, today, ce.event.title.ifBlank { "여행" } + " · 데리러 오는 중", text)
                        }.catch { emit(null) }
                    }) { all -> all.firstOrNull { it != null } }
                }
                .catch { emit(null) }
                .distinctUntilChanged()
                .collect { show(app, it) }
        }
    }

    private fun show(context: Context, live: Live?) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (live == null) { nm.cancel(ID); return }
        // Quiet: it updates often and must not buzz each time.
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "차가 가는 중", NotificationManager.IMPORTANCE_LOW))
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            data = android.net.Uri.parse("carplan://trip/${live.groupId}/${live.eventId}/${live.date}")
        }
        val pi = PendingIntent.getActivity(context, ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(live.title).setContentText(live.text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pi).build()
        runCatching { nm.notify(ID, n) }
    }
}
