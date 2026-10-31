package com.htb.carplan.car

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Motion on the car (정원 2026-10-31: 순간이동 말고 부드럽게). One length everywhere so the screen feels like one thing.
 * Pleos: no animation while driving — [LocalMotion] is false then and everything below snaps.
 */
const val MOTION_MS = 250

val LocalMotion = compositionLocalOf { true }

@Composable
fun <T> carSpec(ms: Int = MOTION_MS): FiniteAnimationSpec<T> = if (LocalMotion.current) tween(ms) else snap()

/** A colour that changes (selected / not) fades instead of flipping. */
@Composable
fun carColor(target: Color, label: String = "carColor"): State<Color> = animateColorAsState(target, carSpec(), label = label)

/** Size changes (a row added, a box opened) push the rest along instead of jumping. */
@Composable
fun Modifier.carSize(): Modifier = if (LocalMotion.current) this.animateContentSize(tween(MOTION_MS)) else this

enum class Entry { SHEET, SCREEN, FADE }

/**
 * Plays an entrance once when [content] first shows: a window grows in slightly ([Entry.SHEET]), a full screen slides
 * in from the right ([Entry.SCREEN]), a band just fades in ([Entry.FADE]). [key] replays it when the thing shown changes.
 */
@Composable
fun CarEnter(entry: Entry, modifier: Modifier = Modifier, key: Any? = Unit, content: @Composable () -> Unit) {
    val motion = LocalMotion.current
    val state = remember(key) { MutableTransitionState(!motion).apply { targetState = true } }
    val enter: EnterTransition = when {
        !motion -> EnterTransition.None
        entry == Entry.SHEET -> fadeIn(tween(MOTION_MS)) + scaleIn(tween(MOTION_MS), initialScale = 0.96f)
        entry == Entry.SCREEN -> fadeIn(tween(MOTION_MS)) + slideInHorizontally(tween(MOTION_MS)) { it / 12 }
        else -> fadeIn(tween(MOTION_MS))
    }
    AnimatedVisibility(visibleState = state, modifier = modifier, enter = enter) { content() }
}

/** Something that comes and goes in place (a notice line, a bar): opens top-down and closes back up. */
@Composable
fun CarAppear(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val motion = LocalMotion.current
    AnimatedVisibility(
        visible = visible, modifier = modifier,
        enter = if (motion) fadeIn(tween(MOTION_MS)) + expandVertically(tween(MOTION_MS), expandFrom = Alignment.Top) else EnterTransition.None,
        exit = if (motion) fadeOut(tween(MOTION_MS)) + shrinkVertically(tween(MOTION_MS), shrinkTowards = Alignment.Top) else ExitTransition.None,
    ) { content() }
}
