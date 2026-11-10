package com.htb.carplan.shared.calendar

/**
 * Memos the app writes itself ("투표로 정함", "다트 · 양천구") are shown as a small grey tag, not as a person's note.
 * [split] returns (tag, the rest of the memo) — tag null for an ordinary memo.
 */
object StopMemo {
    const val VOTE = "투표로 정함"
    const val DART_PREFIX = "다트 · "
    /** Added from the AI 추천 window (one place, or the whole suggested day). */
    const val PICKED = "추천"
    const val AI_PICKED = "AI 추천"

    fun split(memo: String): Pair<String?, String> = when {
        memo == VOTE -> "투표" to ""
        memo.startsWith(DART_PREFIX) -> "다트" to memo.removePrefix(DART_PREFIX)
        memo == PICKED || memo == AI_PICKED -> "추천" to ""
        else -> null to memo
    }
}
