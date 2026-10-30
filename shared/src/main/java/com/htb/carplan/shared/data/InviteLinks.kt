package com.htb.carplan.shared.data

/**
 * Invite links (4장·7장): one short URL on Firebase Hosting that opens the app when installed
 * and shows install instructions otherwise. The car's QR encodes the same URL.
 */
object InviteLinks {
    const val HOST = "carplan-htb.web.app"
    const val SCHEME = "carplan"

    fun url(inviteCode: String) = "https://$HOST/i/$inviteCode"

    /** The invite code inside a link or deep link, or null. Accepts https://host/i/CODE and carplan://i/CODE. */
    fun codeOf(uri: String?): String? {
        if (uri == null) return null
        val m = Regex("(?:https://$HOST|$SCHEME:/)/i/([A-Za-z0-9]{4,12})").find(uri) ?: return null
        return Codes.normalize(m.groupValues[1])
    }
}
