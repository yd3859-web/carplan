package com.htb.carplan.shared.net

import com.htb.carplan.shared.calendar.CategoryColors
import com.htb.carplan.shared.model.Event
import com.htb.carplan.shared.model.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/**
 * 2장 Q05: the user's primary Google Calendar, read only. Events come back as our [Event] shape with
 * categoryId = "google" so the month grid and day panel draw them like any other event; nothing is written back.
 */
class GoogleCalendarApi {
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    /** Events overlapping [from]..[to] (inclusive) from the primary calendar. [accessToken] must carry calendar.readonly. */
    suspend fun events(accessToken: String, from: LocalDate, to: LocalDate): List<Event> = withContext(Dispatchers.IO) {
        val zone = java.time.ZoneId.systemDefault()
        val url = "https://www.googleapis.com/calendar/v3/calendars/primary/events".toHttpUrl().newBuilder()
            .addQueryParameter("timeMin", from.atStartOfDay(zone).toOffsetDateTime().toString())
            .addQueryParameter("timeMax", to.plusDays(1).atStartOfDay(zone).toOffsetDateTime().toString())
            .addQueryParameter("singleEvents", "true")
            .addQueryParameter("orderBy", "startTime")
            .addQueryParameter("maxResults", "250")
            .build()
        val req = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").build()
        val body = http.newCall(req).execute().use { res ->
            val b = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IllegalStateException("Google Calendar ${res.code}: ${b.take(200)}")
            b
        }
        val items = JSONObject(body).optJSONArray("items") ?: return@withContext emptyList()
        (0 until items.length()).mapNotNull { i -> items.getJSONObject(i).toEvent(zone) }
    }

    private fun JSONObject.toEvent(zone: java.time.ZoneId): Event? {
        if (optString("status") == "cancelled") return null
        val start = optJSONObject("start") ?: return null
        val end = optJSONObject("end")
        val allDay = start.has("date")
        val startDate: String
        val endDate: String
        var startTime: String? = null
        if (allDay) {
            startDate = start.getString("date")
            // Google's all-day end is exclusive (the next morning).
            endDate = end?.optString("date")?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it).minusDays(1).toString() } ?: startDate
        } else {
            val s = OffsetDateTime.parse(start.getString("dateTime")).atZoneSameInstant(zone)
            val e = end?.optString("dateTime")?.takeIf { it.isNotBlank() }?.let { OffsetDateTime.parse(it).atZoneSameInstant(zone) }
            startDate = s.toLocalDate().toString()
            endDate = (e?.toLocalDate() ?: s.toLocalDate()).toString()
            startTime = "%02d:%02d".format(s.hour, s.minute)
        }
        val location = optString("location").takeIf { it.isNotBlank() }
        return Event(
            id = "g-" + optString("id"),
            categoryId = CategoryColors.GOOGLE_ID,
            title = optString("summary").ifBlank { "(제목 없음)" },
            startDate = startDate, endDate = endDate, allDay = allDay, startTime = startTime,
            place = location?.let { Place(name = it) },
            memo = optString("description").take(200),
            createdBy = "google",
        )
    }
}
