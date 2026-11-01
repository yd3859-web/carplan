package com.htb.carplan.phone.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 손⑦ 사진 크게 보기 (갤러리 앱처럼): the photos of a stop or a day, one at a time on black. Sideways = next photo,
 * two fingers or a double tap = zoom (then one finger moves the zoomed photo), pull down = close. Black is the one
 * exception to the white screens — a photo reads best on it.
 */
@Composable
fun PhotoViewer(urls: List<String>, start: Int, onDismiss: () -> Unit) {
    if (urls.isEmpty()) return
    val pager = rememberPagerState(initialPage = start.coerceIn(0, urls.lastIndex)) { urls.size }
    var zoomed by remember { mutableStateOf(false) }
    val pull = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1f - abs(pull.value) / 900f).coerceIn(0.3f, 1f)))) {
            HorizontalPager(
                pager, userScrollEnabled = !zoomed, key = { urls[it] },
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer { translationY = pull.value }
                    // Pull down (or up) to close, only when not zoomed in.
                    .pointerInput(zoomed) {
                        if (zoomed) return@pointerInput
                        detectVerticalDragGestures(
                            onDragEnd = { scope.launch { if (abs(pull.value) > 160.dp.toPx()) onDismiss() else pull.animateTo(0f) } },
                            onDragCancel = { scope.launch { pull.animateTo(0f) } },
                        ) { change, dy -> change.consume(); scope.launch { pull.snapTo(pull.value + dy) } }
                    },
            ) { page ->
                ZoomablePhoto(urls[page], active = page == pager.currentPage, onZoom = { zoomed = it })
            }
            Text(
                "${pager.currentPage + 1} / ${urls.size}", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopStart).systemBarsPadding().padding(20.dp),
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(8.dp)) {
                Text("닫기", color = Color.White, fontSize = 17.sp)
            }
        }
    }
}

@Composable
private fun ZoomablePhoto(url: String, active: Boolean, onZoom: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // A page swiped away comes back unzoomed.
    if (!active && scale != 1f) { scale = 1f; offset = Offset.Zero }
    fun set(s: Float, o: Offset) {
        scale = s.coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else o
        onZoom(scale > 1f)
    }
    AsyncImage(
        model = url, contentDescription = null, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { if (scale > 1f) set(1f, Offset.Zero) else set(2.5f, Offset.Zero) }) }
            .pointerInput(Unit) {
                // Two fingers zoom; once zoomed, one finger moves the photo. Unzoomed one-finger moves go to the pager.
                detectZoom(isZoomed = { scale > 1f }) { pan, zoom -> set(scale * zoom, offset + pan) }
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
    )
}

/** Like detectTransformGestures, but leaves one-finger drags alone while unzoomed (so paging and pull-to-close work). */
private suspend fun PointerInputScope.detectZoom(isZoomed: () -> Boolean, onGesture: (pan: Offset, zoom: Float) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2 || isZoomed()) {
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                if (zoom != 1f || pan != Offset.Zero) {
                    onGesture(pan, zoom)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}
