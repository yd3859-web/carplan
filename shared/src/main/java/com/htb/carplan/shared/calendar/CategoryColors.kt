package com.htb.carplan.shared.calendar

import com.htb.carplan.shared.model.BuiltInCategory
import com.htb.carplan.shared.model.CustomCategory

/** ARGB colors for categories (Q03): built-ins are fixed, custom ones pick from [PALETTE]. */
object CategoryColors {
    /** 2장 Q05: events read from the user's Google Calendar (phone only, read-only, not stored in Firestore). */
    const val GOOGLE_ID = "google"
    const val GOOGLE_LABEL = "구글"

    val BUILT_IN: Map<String, Long> = mapOf(
        BuiltInCategory.TRIP.id to 0xFF7EA6F0,
        BuiltInCategory.CAR.id to 0xFF5FC2A2,
        BuiltInCategory.OTHER.id to 0xFF9C8CF0,
        GOOGLE_ID to 0xFFE0A33B,
    )

    /** The 8 colors a user can give a category they add. */
    val PALETTE: List<Long> = listOf(
        0xFFEFA0B4, 0xFFF4B66E, 0xFFE3CB5C, 0xFF8FCF86,
        0xFF6FC4D8, 0xFF7EA6F0, 0xFFB38CEF, 0xFFAEB3BD,
    )

    fun of(categoryId: String, custom: List<CustomCategory>): Long =
        BUILT_IN[categoryId] ?: custom.firstOrNull { it.id == categoryId }?.color ?: PALETTE.last()

    fun label(categoryId: String, custom: List<CustomCategory>): String =
        (if (categoryId == GOOGLE_ID) GOOGLE_LABEL else null)
            ?: BuiltInCategory.entries.firstOrNull { it.id == categoryId }?.label
            ?: custom.firstOrNull { it.id == categoryId }?.name
            ?: "기타"
}
