package com.htb.carplan.car

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val WheelRow = 76.dp
private val Corner = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
private val Hours = (0..23).toList()
private val Minutes = (0..55 step 5).toList()

/** "HH:mm" → the nearest wheel values (minutes in 5-minute steps). Null → [fallback]. */
internal fun wheelTime(time: String?, fallback: Pair<Int, Int>): Pair<Int, Int> {
    val p = time?.split(":")?.mapNotNull { it.toIntOrNull() }
    if (p == null || p.size < 2) return fallback
    return p[0].coerceIn(0, 23) to Minutes.minBy { kotlin.math.abs(it - p[1]) }
}

/** 시 · 분 wheels side by side: scroll (or tap a row) to choose. The middle row between the two lines is the choice. */
@Composable
internal fun CarTimeWheel(hour: Int, minute: Int, onChange: (Int, Int) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalCarColors.current
    Box(modifier.fillMaxWidth().height(WheelRow * 3), contentAlignment = Alignment.Center) {
        // The chosen row: a thin frame, no fill (Pleos — lines, not colored boxes).
        Box(Modifier.fillMaxWidth().height(WheelRow).border(1.dp, c.line, Corner))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Wheel(Hours, hour, { "%02d".format(it) }) { onChange(it, minute) }
            Text("시", color = c.soft, fontSize = 28.sp, modifier = Modifier.width(56.dp), textAlign = TextAlign.Center)
            Wheel(Minutes, minute, { "%02d".format(it) }) { onChange(hour, it) }
            Text("분", color = c.soft, fontSize = 28.sp, modifier = Modifier.width(56.dp), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Wheel(values: List<Int>, selected: Int, label: (Int) -> String, onSelect: (Int) -> Unit) {
    val c = LocalCarColors.current
    val start = values.indexOf(selected).coerceAtLeast(0)
    val state = rememberLazyListState(start)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val select by rememberUpdatedState(onSelect) // the other wheel's value changes; don't keep the first one
    val half = with(LocalDensity.current) { WheelRow.toPx() / 2 }
    val center by remember { derivedStateOf { (state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > half) 1 else 0).coerceIn(0, values.lastIndex) } }
    LaunchedEffect(state) {
        var last = center
        snapshotFlow { center }.collect { i ->
            if (i != last) { last = i; haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); select(values[i]) }
        }
    }
    LazyColumn(
        state = state, flingBehavior = rememberSnapFlingBehavior(state),
        contentPadding = PaddingValues(vertical = WheelRow), horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(120.dp).height(WheelRow * 3),
    ) {
        itemsIndexed(values) { i, v ->
            val on = i == center
            Box(Modifier.width(120.dp).height(WheelRow).clickable { scope.launch { state.animateScrollToItem(i) } }, contentAlignment = Alignment.Center) {
                Text(label(v), color = if (on) c.ink else c.soft, fontSize = if (on) 40.sp else 30.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}
