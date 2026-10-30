package com.htb.carplan.phone.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The day rail (from the sketch docs/캘린더예시/타임라인_스케치.png): one vertical line with a node per stop.
 * Dotted = still to drive, solid grey = driven. Used by the trip editor, 그날 동선 and the car.
 */
enum class NodeState { PLANNED, NEXT, VISITED }

val RailPassed = Color(0xFFB9BEC4)

/** Draws the rail segments above/below a node whose centre sits at [nodeCenter] from the top. */
fun Modifier.railLine(nodeCenter: Dp, drawAbove: Boolean, abovePassed: Boolean, belowFill: Float, drawBelow: Boolean = true): Modifier = drawBehind {
    val x = size.width / 2
    val cy = nodeCenter.toPx()
    val w = 3.dp.toPx()
    val dots = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 7.dp.toPx()), 0f)
    if (drawAbove) drawLine(if (abovePassed) RailPassed else Palette.Line, Offset(x, 0f), Offset(x, cy), w, StrokeCap.Round, if (abovePassed) null else dots)
    if (drawBelow) {
        drawLine(Palette.Line, Offset(x, cy), Offset(x, size.height), w, StrokeCap.Round, dots)
        if (belowFill > 0f) drawLine(RailPassed, Offset(x, cy), Offset(x, cy + (size.height - cy) * belowFill), w, StrokeCap.Round)
    }
}

/** The node: empty ring (planned), filled blue with a slow breathing halo (next), grey check (visited). */
@Composable
fun RailNode(state: NodeState, size: Dp = 26.dp, modifier: Modifier = Modifier, halo: Boolean = true) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (state == NodeState.NEXT && halo) {
            val t = rememberInfiniteTransition(label = "halo")
            val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3000), RepeatMode.Restart), label = "haloP")
            Box(Modifier.size(size).scale(1f + p * 0.9f).clip(CircleShape).background(Palette.Teal.copy(alpha = (1f - p) * 0.25f)))
        }
        when (state) {
            NodeState.PLANNED -> Box(Modifier.size(size - 4.dp).clip(CircleShape).background(Palette.Background).border(2.dp, Color(0xFFAEB4BA), CircleShape))
            NodeState.NEXT -> Box(Modifier.size(size).clip(CircleShape).background(Palette.Teal))
            NodeState.VISITED -> Box(Modifier.size(size - 6.dp).clip(CircleShape).background(RailPassed), contentAlignment = Alignment.Center) {
                Text("✓", color = Color.White, fontSize = (size.value * 0.46f).sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Which node is "next": the first unreached stop once the day is being driven. */
fun nodeState(visited: Boolean, isNext: Boolean) = when { visited -> NodeState.VISITED; isNext -> NodeState.NEXT; else -> NodeState.PLANNED }

/** Speech-bubble outline with a small tail pointing left at the rail. */
fun Modifier.speechBubble(ghost: Boolean = false): Modifier = this.drawBehind {
    val r = 4.dp.toPx()
    val tail = 8.dp.toPx()
    val color = if (ghost) Color(0xFFD9DDE2) else Color(0xFFC9CED4)
    val path = Path().apply {
        moveTo(tail + r, 0f)
        lineTo(size.width - r, 0f); quadraticTo(size.width, 0f, size.width, r)
        lineTo(size.width, size.height - r); quadraticTo(size.width, size.height, size.width - r, size.height)
        lineTo(tail + r, size.height); quadraticTo(tail, size.height, tail, size.height - r)
        lineTo(tail, size.height / 2 + tail * 0.7f)
        lineTo(0f, size.height / 2)
        lineTo(tail, size.height / 2 - tail * 0.7f)
        lineTo(tail, r); quadraticTo(tail, 0f, tail + r, 0f)
        close()
    }
    drawPath(path, Palette.Background)
    drawPath(path, color, style = Stroke(1.dp.toPx(), pathEffect = if (ghost) PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) else null))
}.padding(start = 8.dp)
