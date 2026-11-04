package com.htb.carplan.phone.group

import com.htb.carplan.shared.data.InviteLinks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Invite codes arriving from a link, a QR scan or typing, waiting for the "참여할까요?" dialog. */
object InviteInbox {
    private val _code = MutableStateFlow<String?>(null)
    val code: StateFlow<String?> = _code

    /** Accepts a full link (https or carplan://) and keeps its code. Ignores anything else. */
    fun offer(uri: String?) { InviteLinks.codeOf(uri)?.let { _code.value = it } }

    fun offerCode(code: String) { if (code.isNotBlank()) _code.value = code }

    fun clear() { _code.value = null }

    /** 17장: a tapped push wants a trip day opened — carplan://trip/{groupId}/{eventId}/{date}[?parking={stopId}]. The 4th
     *  item is the stop to find parking around ("주차장 찾아봐 주세요" push), or "" for none. */
    private val _openTrip = MutableStateFlow<List<String>?>(null)
    val openTrip: StateFlow<List<String>?> = _openTrip

    fun offerTrip(uri: String?) {
        val m = Regex("carplan://trip/([^/?]+)/([^/?]+)/([0-9-]+)(?:\\?parking=([^&]+))?").find(uri ?: return) ?: return
        _openTrip.value = listOf(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues.getOrElse(4) { "" })
    }

    fun clearTrip() { _openTrip.value = null }

    /** Straight to a trip day (after joining an event's own group by its QR·link). */
    fun showTrip(groupId: String, eventId: String, date: String) { _openTrip.value = listOf(groupId, eventId, date, "") }

    /** A push tapped while the app was in the background: the system shows it itself and hands its data as extras. */
    fun offerPushExtras(extras: android.os.Bundle?) {
        val g = extras?.getString("groupId") ?: return
        val e = extras.getString("eventId") ?: return
        val d = extras.getString("date") ?: return
        offerTrip("carplan://trip/$g/$e/$d" + (extras.getString("stopId")?.takeIf { extras.getString("kind") == "parking" }?.let { "?parking=$it" } ?: ""))
    }
}
