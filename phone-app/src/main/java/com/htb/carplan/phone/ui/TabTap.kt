package com.htb.carplan.phone.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember

/** Counts taps on the tab that is already showing (AppRoot's tab bar). */
val LocalTabTapAgain = compositionLocalOf { 0 }

/**
 * 손③ (인스타·카톡·아이폰 캘린더): tapping the tab you are on brings it back to its start — a screen opened on top
 * closes, a list goes to the top, the calendar goes to today. Taps counted before this tab showed are not replayed.
 */
@Composable
fun OnTabTapAgain(action: suspend () -> Unit) {
    val n = LocalTabTapAgain.current
    val start = remember { n }
    LaunchedEffect(n) { if (n != start) action() }
}
