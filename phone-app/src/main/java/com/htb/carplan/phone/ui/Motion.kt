package com.htb.carplan.phone.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Phone motion, the same language as the car (car/CarMotion.kt, 팀장 2026-10-31 "순간이동 말고 부드럽게"): one length
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

/**
 * 손① 뒤로 손짓 (아이폰 왼쪽 끝 밀기, 안드로이드 예측 뒤로): a full screen drawn over the one it came from. The back
 * gesture moves it right with the finger and the screen underneath shows through; let go early and it slides back.
 * The ← button gets the same slide ([content]'s `back`). Must be emitted last in a full-size Box parent (the tab's
 * Box) so the screen underneath stays composed under it. Nested ones (그룹 → 여행) close the top one first.
 */
@Composable
fun BackScreen(onBack: () -> Unit, content: @Composable (back: () -> Unit) -> Unit) {
    val scope = rememberCoroutineScope()
    val gone = remember { Animatable(0f) } // 0 = in place, 1 = slid out to the right
    var leaving by remember { mutableStateOf(false) }
    val leave: () -> Unit = {
        if (!leaving) {
            leaving = true
            scope.launch { gone.animateTo(1f, tween(MOTION_MS)); onBack() }
        }
    }
    PredictiveBackHandler(enabled = !leaving) { progress ->
        try {
            progress.collect { e -> gone.snapTo(e.progress) }
            leaving = true
            gone.animateTo(1f, tween((MOTION_MS * (1f - gone.value)).toInt().coerceAtLeast(80)))
            onBack()
        } catch (e: CancellationException) {
            scope.launch { gone.animateTo(0f, tween(MOTION_MS)) }
            throw e
        }
    }
    Box(Modifier.fillMaxSize()) {
        // The screen underneath, a little dimmed, brightening as this one moves away.
        if (gone.value > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.16f * (1f - gone.value))))
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { translationX = gone.value * size.width; shadowElevation = if (gone.value > 0f) 16.dp.toPx() else 0f }
                // Opaque: a screen that draws no ground of its own must not show the one underneath (그룹 화면, 2026-11-11).
                .background(Palette.Background)
                // Touches stop here: the screen underneath must not get them.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
        ) { content(leave) }
    }
}

/**
 * Swipe sideways to step back or forward (달 · 날짜): finger left = next, right = previous, once past 64dp. Vertical
 * scrolling and long-press dragging underneath keep working (the gesture locks to one direction first).
 */
@Composable
fun Modifier.sideSwipe(key: Any?, onPrev: () -> Unit, onNext: () -> Unit): Modifier {
    // 손⑧: a light tick when the page actually turns.
    val haptic = LocalHapticFeedback.current
    return pointerInput(key) {
        var total = 0f
        val threshold = 64.dp.toPx()
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                if (total < -threshold) { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onNext() }
                else if (total > threshold) { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onPrev() }
            },
        ) { change, dx -> total += dx; change.consume() }
    }
}
