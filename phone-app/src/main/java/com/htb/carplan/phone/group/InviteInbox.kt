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

    /** 17장: a tapped push wants a trip day opened — carplan://trip/{groupId}/{eventId}/{date}. */
    private val _openTrip = MutableStateFlow<List<String>?>(null)
    val openTrip: StateFlow<List<String>?> = _openTrip

    fun offerTrip(uri: String?) {
        val m = Regex("carplan://trip/([^/]+)/([^/]+)/([0-9-]+)").find(uri ?: return) ?: return
        _openTrip.value = listOf(m.groupValues[1], m.groupValues[2], m.groupValues[3])
    }

    fun clearTrip() { _openTrip.value = null }

    /** Straight to a trip day (after joining an event's own group by its QR·link). */
    fun showTrip(groupId: String, eventId: String, date: String) { _openTrip.value = listOf(groupId, eventId, date) }
}
