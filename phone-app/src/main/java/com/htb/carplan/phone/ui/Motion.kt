package com.htb.carplan.phone.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Phone motion, the same language as the car (car/CarMotion.kt, 정원 2026-10-31 "순간이동 말고 부드럽게"): one length
 * everywhere — a window grows in slightly, a full screen slides in from the right, tabs slide sideways.
 */
const val MOTION_MS = 250

enum class Entry { SHEET, SCREEN }

/** Plays the entrance once when [content] first shows; [key] replays it when the thing shown changes. */
@Composable
fun Enter(entry: Entry, modifier: Modifier = Modifier, key: Any? = Unit, content: @Composable () -> Unit) {
    val state = remember(key) { MutableTransitionState(false).apply { targetState = true } }
    val enter = when (entry) {
        Entry.SHEET -> fadeIn(tween(MOTION_MS)) + scaleIn(tween(MOTION_MS), initialScale = 0.96f)
        Entry.SCREEN -> fadeIn(tween(MOTION_MS)) + slideInHorizontally(tween(MOTION_MS)) { it / 12 }
    }
    AnimatedVisibility(visibleState = state, modifier = modifier, enter = enter) { content() }
}
