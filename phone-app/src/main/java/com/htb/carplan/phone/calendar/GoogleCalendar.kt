package com.htb.carplan.phone.calendar

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.htb.carplan.shared.data.CalendarEvent
import com.htb.carplan.shared.net.GoogleCalendarApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.time.LocalDate

/**
 * 2장 Q05: "구글 캘린더 같이 보기" — read only. The token comes from Play Services' AuthorizationClient
 * (calendar.readonly); the first time Google shows a consent sheet (the [PendingIntent] the caller launches).
 * Events live only in memory here (never in Firestore) and are merged into the calendar's event list.
 */
object GoogleCalendar {
    private const val PREFS = "google_calendar"
    private const val SCOPE = "https://www.googleapis.com/auth/calendar.readonly"
    const val GROUP_ID = "google"

    private val api = GoogleCalendarApi()
    private val _events = MutableStateFlow<List<CalendarEvent>>(emptyList())
    val events: StateFlow<List<CalendarEvent>> = _events.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    /** Last problem, for the settings tab ("동의가 필요해요", "불러오기 실패 …"). */
    val status: StateFlow<String?> = _status.asStateFlow()
    private var token: String? = null
    private var tokenAt = 0L

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply()
        if (!on) { _events.value = emptyList(); token = null; _status.value = null }
    }

    /** Needs the user's consent: launch this and call [finishAuthorization] with the result. */
    class ConsentNeeded(val pendingIntent: PendingIntent) : Exception("구글 동의 필요")

    /** An access token, silently when already granted; throws [ConsentNeeded] the first time. */
    suspend fun authorize(context: Context): String {
        token?.takeIf { System.currentTimeMillis() - tokenAt < 50 * 60_000 }?.let { return it }
        val req = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()
        val res = Identity.getAuthorizationClient(context).authorize(req).await()
        if (res.hasResolution()) throw ConsentNeeded(res.pendingIntent!!)
        return remember(res.accessToken ?: error("토큰 없음"))
    }

    fun finishAuthorization(context: Context, data: Intent?): Boolean {
        val res = runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data) }.getOrNull() ?: return false
        res.accessToken?.let { remember(it); return true }
        return false
    }

    private fun remember(t: String): String { token = t; tokenAt = System.currentTimeMillis(); return t }

    /** Load [from]..[to] into [events] (the calendar's visible grid). Quiet when consent is still missing. */
    suspend fun refresh(context: Context, from: LocalDate, to: LocalDate) {
        if (!enabled(context)) return
        runCatching {
            val t = authorize(context)
            _events.value = api.events(t, from, to).map { CalendarEvent(GROUP_ID, it) }
            _status.value = null
        }.onFailure { e ->
            _status.value = when (e) {
                is ConsentNeeded -> "구글 캘린더 읽기 동의가 필요해요 — 설정 탭에서 다시 켜 주세요"
                else -> "구글 캘린더 불러오기 실패: ${e.message?.take(80)}"
            }
            if (e !is ConsentNeeded) token = null
        }
    }
}
