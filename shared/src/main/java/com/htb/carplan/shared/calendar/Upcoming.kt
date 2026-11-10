package com.htb.carplan.shared.calendar

import com.htb.carplan.shared.model.Event

/**
 * B10 (2026-11-10): a 동네 already planned soon is not recommended again — 토요일 담양 여행이 있는데 오늘도 담양을
 * 권하지 않게. Matching is by the 동네's core word ("담양군" → "담양") in an upcoming event's title or place.
 */
object Upcoming {
    /** What an event says about where it goes: title, place name and address. */
    fun texts(events: List<Event>): List<String> =
        events.filter { !it.deleted }.flatMap { e -> listOfNotNull(e.title, e.place?.name, e.place?.address) }.filter { it.isNotBlank() }

    fun covers(region: String, texts: List<String>): Boolean {
        val core = core(region) ?: return false
        return texts.any { it.replace(" ", "").contains(core) }
    }

    /** "전남 담양군" → "담양", "제주시" → "제주", "강릉" → "강릉". Null for a word too short to tell. */
    fun core(region: String): String? {
        val last = region.trim().split(' ').lastOrNull { it.isNotBlank() } ?: return null
        val cut = if (last.length >= 3 && last.last() in "시군구읍면동") last.dropLast(1) else last
        return cut.takeIf { it.length >= 2 }
    }
}
