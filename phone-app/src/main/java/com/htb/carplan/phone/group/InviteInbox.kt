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
}
