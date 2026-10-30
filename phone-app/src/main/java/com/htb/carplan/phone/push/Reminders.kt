package com.htb.carplan.phone.push

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.htb.carplan.shared.calendar.Repeat
import com.htb.carplan.shared.data.CalendarEvent
import com.htb.carplan.shared.model.BuiltInCategory
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 2장 [알림] 칩: an event's "n분 전" fires as a local notification. The calendar reschedules every
 * time its upcoming events change (next 14 days, repeats expanded); nothing runs on a server.
 * Exact alarms: a calendar app may hold USE_EXACT_ALARM (33+); on 31–32 we fall back to inexact when not allowed.
 */
object Reminders {
    private const val PREFS = "reminders"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_CODES = "codes"
    private const val DAYS_AHEAD = 14L
    private const val ALL_DAY_HOUR = 9

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean, events: List<CalendarEvent>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        schedule(context, events)
    }

    /** Replace every pending reminder with the ones [events] need now. */
    fun schedule(context: Context, events: List<CalendarEvent>) {
        val am = context.getSystemService(AlarmManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet(KEY_CODES, emptySet()).orEmpty().forEach { code -> am.cancel(pending(context, code.toInt(), null)) }
        val codes = HashSet<String>()
        if (enabled(context)) {
            val now = LocalDateTime.now()
            val zone = ZoneId.systemDefault()
            for (ce in events) {
                val e = ce.event
                val minutes = e.reminderMinutes ?: continue
                if (e.deleted) continue
                val start = runCatching { LocalDate.parse(e.startDate) }.getOrNull() ?: continue
                val dates = if (e.repeat != null) (0..DAYS_AHEAD).map { LocalDate.now().plusDays(it) }.filter { Repeat.occursOn(e, it) } else listOf(start)
                for (d in dates) {
                    if (d.isAfter(LocalDate.now().plusDays(DAYS_AHEAD))) continue
                    val time = e.startTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.of(ALL_DAY_HOUR, 0)
                    val fireAt = LocalDateTime.of(d, time).minusMinutes(minutes.toLong())
                    if (!fireAt.isAfter(now)) continue
                    val code = (e.id + d.toString()).hashCode()
                    val whenText = if (e.startTime != null) "${time.hour}:${"%02d".format(time.minute)}" else "오늘"
                    val body = buildString {
                        append(if (minutes == 0) "지금 시작" else "${reminderText(minutes)} · $whenText")
                        e.place?.name?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                    }
                    val intent = Intent(context, ReminderReceiver::class.java).apply {
                        putExtra("title", e.title.ifBlank { "일정" }); putExtra("body", body)
                        if (e.categoryId == BuiltInCategory.TRIP.id) { putExtra("groupId", ce.groupId); putExtra("eventId", e.id); putExtra("date", d.toString()) }
                    }
                    val pi = pending(context, code, intent)
                    val at = fireAt.atZone(zone).toInstant().toEpochMilli()
                    if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                    else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                    codes += code.toString()
                }
            }
        }
        prefs.edit().putStringSet(KEY_CODES, codes).apply()
    }

    private fun pending(context: Context, code: Int, intent: Intent?) = PendingIntent.getBroadcast(
        context, code, intent ?: Intent(context, ReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun reminderText(minutes: Int) = when {
        minutes == 0 -> "정각"
        minutes % 1440 == 0 -> "${minutes / 1440}일 전"
        minutes % 60 == 0 -> "${minutes / 60}시간 전"
        else -> "${minutes}분 전"
    }
}

/** Posts the reminder through the same channel as pushes (tap → the trip day when it is a trip). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val data = listOf("groupId", "eventId", "date").mapNotNull { k -> intent.getStringExtra(k)?.let { k to it } }.toMap()
        CarplanMessagingService.show(context, intent.getStringExtra("title") ?: "일정", intent.getStringExtra("body") ?: "", data)
    }
}
