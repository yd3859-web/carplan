package com.htb.carplan.phone.trip

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.htb.carplan.shared.model.Place

/** "음식점 > 한식 > 국밥" → "한식 · 국밥" (the first level is the tab already). */
fun Place.kindLine(): String? = category.split(">").map { it.trim() }.filter { it.isNotBlank() }.drop(1).takeLast(2).joinToString(" · ").ifBlank { null }

/**
 * 평점·메뉴·사진·리뷰: the Kakao Map page of the place (정원 2026-11-04 "평점이나 뭐 파는지 볼 수 있나" — the search API
 * gives none of them, the page has all). Opens in the Kakao Map app if installed, else the browser.
 */
fun openPlacePage(context: Context, p: Place) {
    val url = p.kakaoUrl ?: "https://map.kakao.com/link/search/${Uri.encode(p.name)}"
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
