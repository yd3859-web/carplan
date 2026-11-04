package com.htb.carplan.phone.record

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.MapMarker
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.phone.ui.PhotoViewer
import com.htb.carplan.phone.ui.RailPassed
import com.htb.carplan.phone.ui.SimpleMap
import com.htb.carplan.phone.ui.speechBubble
import com.htb.carplan.shared.model.Comment
import com.htb.carplan.shared.model.Record
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * 14장 기록 탭 — 읽는 일기 한 장 (정원 2026-11-04: "레일이 한 칸씩 나오면서 어디 갔는지, 그때 코멘트와 사진, 내리면
 * 이어지는 듯이"). The day's rail is drawn as you read: scrolling a place into view grows the line down to it, its
 * dot lands, then the place, the photos and what people said appear, and the line heads on toward the next place.
 * A place already shown stays put when you scroll back. [이어 쓰기] opens the editing rail.
 */
@Composable
fun DiaryPage(
    book: DiaryBook,
    page: DiaryDay,
    vm: RecordTabViewModel,
    onBack: () -> Unit,
    onPage: (DiaryDay) -> Unit,
    onEdit: () -> Unit,
) {
    val records = book.pages.firstOrNull { it.first.date == page.date }?.second.orEmpty()
    val comments by produceState<List<Comment>>(emptyList(), page) { value = vm.comments(page) }
    val names by vm.names.collectAsStateWithLifecycle()
    val state = rememberLazyListState()
    // Items whose entrance already played (by index in the list).
    val seen = remember(page) { mutableSetOf<Int>() }
    // The places play one after another: an item starts only once the one above it has finished.
    val finished = remember(page) { androidx.compose.runtime.mutableIntStateOf(0) }
    var viewing by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    val d = LocalDate.parse(page.date)
    val index = book.pages.indexOfFirst { it.first.date == page.date }
    val prev = book.pages.getOrNull(index - 1)?.first
    val next = book.pages.getOrNull(index + 1)?.first

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), state = state) {
            item(key = "head") {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        IconButton(onClick = onBack, modifier = Modifier.padding(start = 0.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = Palette.TextStrong) }
                        Spacer(Modifier.weight(1f))
                        if (book.pages.size > 1) {
                            Text("‹", fontSize = 26.sp, color = if (prev != null) Palette.TextStrong else Palette.Line,
                                modifier = Modifier.clip(Corner).clickable(enabled = prev != null) { prev?.let(onPage) }.padding(horizontal = 14.dp, vertical = 4.dp))
                            Text("${page.dayNo}일차", fontSize = 15.sp, color = Palette.TextSoft)
                            Text("›", fontSize = 26.sp, color = if (next != null) Palette.TextStrong else Palette.Line,
                                modifier = Modifier.clip(Corner).clickable(enabled = next != null) { next?.let(onPage) }.padding(horizontal = 14.dp, vertical = 4.dp))
                        }
                    }
                    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 8.dp)) {
                        Text("${d.dayOfMonth}", fontSize = 56.sp, fontWeight = FontWeight.Black, color = Palette.TextStrong, lineHeight = 56.sp)
                        Column(Modifier.padding(start = 12.dp, bottom = 8.dp)) {
                            Text("${d.year}년 ${d.monthValue}월", fontSize = 15.sp, color = Palette.TextSoft)
                            Text(d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.KOREAN), fontSize = 17.sp, color = Palette.TextStrong)
                        }
                    }
                    Text(if (page.isLog) "일상" else page.title.ifBlank { "여행" }, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.padding(top = 12.dp))
                    val with = book.memberIds.filter { it != vm.me() }.map { names[it]?.ifBlank { null } ?: "일행" }.distinct()
                    val photos = records.sumOf { it.photoUrls.size }
                    Text(buildList {
                        add("${records.map { it.place.name }.distinct().size}곳")
                        if (photos > 0) add("사진 ${photos}장")
                        if (with.isNotEmpty()) add("함께 · " + with.joinToString("·"))
                    }.joinToString("  ·  "), fontSize = 15.sp, color = Palette.ChipText, modifier = Modifier.padding(top = 2.dp))
                    val pts = records.filter { it.place.lat != 0.0 || it.place.lng != 0.0 }
                    if (pts.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        SimpleMap(
                            centerLat = pts.first().place.lat, centerLng = pts.first().place.lng, zoom = 13,
                            markers = pts.mapIndexed { i, r -> MapMarker(r.place.lat, r.place.lng, "${i + 1}", MapMarker.Kind.STOP) },
                            path = pts.map { it.place.lat to it.place.lng },
                            fit = if (pts.size >= 2) pts.map { it.place.lat to it.place.lng } else emptyList(),
                            height = 200.dp,
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
            itemsIndexed(records, key = { _, r -> r.id.ifBlank { r.place.name } }) { i, r ->
                StoryStop(
                    number = i + 1, r = r, next = records.getOrNull(i + 1), first = i == 0,
                    said = comments.filter { r.stopId != null && it.stopId == r.stopId },
                    nameOf = { uid -> if (uid == vm.me()) "나" else names[uid]?.ifBlank { null } ?: "일행" },
                    state = state, itemIndex = i + 1, seen = seen, finished = finished,
                    onPhoto = { at -> viewing = r.photoUrls to at },
                )
            }
            item(key = "end") {
                StoryEnd(records, state, itemIndex = records.size + 1, seen = seen, finished = finished, next = next, onNext = { next?.let(onPage) }, onEdit = onEdit)
            }
        }
    }
    viewing?.let { (urls, at) -> PhotoViewer(urls, at) { viewing = null } }
}

private val RailX = 18.dp
private val NodeY = 14.dp

/**
 * This item's turn: the one above has finished, or it has gone off the top (flung past — then it must not hold the
 * rest back). Returns (turn, catchingUp): catching up = the item is already high on screen, so it plays quicker.
 */
@Composable
private fun myTurn(state: LazyListState, itemIndex: Int, finished: androidx.compose.runtime.MutableIntState): Pair<Boolean, Boolean> {
    val t by remember(state, itemIndex) {
        derivedStateOf {
            val info = state.layoutInfo
            val me = info.visibleItemsInfo.firstOrNull { it.index == itemIndex }
            val visible = me != null && (me.offset < info.viewportEndOffset * 0.82f || !state.canScrollForward)
            val turn = visible && (finished.intValue >= itemIndex - 1 || state.firstVisibleItemIndex >= itemIndex)
            turn to (me != null && me.offset < info.viewportEndOffset * 0.4f)
        }
    }
    return t
}

@Composable
private fun StoryStop(
    number: Int, r: Record, next: Record?, first: Boolean, said: List<Comment>, nameOf: (String?) -> String,
    state: LazyListState, itemIndex: Int, seen: MutableSet<Int>, finished: androidx.compose.runtime.MutableIntState, onPhoto: (Int) -> Unit,
) {
    val done = itemIndex in seen
    val line = remember { Animatable(if (done) 1f else 0f) }   // the rail coming down to this place
    val dot = remember { Animatable(if (done) 1f else 0f) }    // the dot landing
    val body = remember { Animatable(if (done) 1f else 0f) }   // place name and time
    val extra = remember { Animatable(if (done) 1f else 0f) }  // photos, the line someone wrote, comments
    val tail = remember { Animatable(if (done) 1f else 0f) }   // the rail heading on to the next place
    val (show, quick) = myTurn(state, itemIndex, finished)
    LaunchedEffect(show) {
        if (!show || itemIndex in seen) return@LaunchedEffect
        seen += itemIndex
        val k = if (quick) 0.45f else 1f
        fun ms(v: Int) = (v * k).toInt()
        // Scrolled away mid-way (the item left composition): the next one must not wait for it.
        try {
            coroutineScope {
                if (!first) line.animateTo(1f, tween(ms(260), easing = FastOutSlowInEasing)) else line.snapTo(1f)
                launch { dot.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow)) }
                body.animateTo(1f, tween(ms(240)))
                extra.animateTo(1f, tween(ms(300)))
                tail.animateTo(1f, tween(ms(420), easing = FastOutSlowInEasing))
            }
        } finally { finished.intValue = maxOf(finished.intValue, itemIndex) }
    }
    val ink = Palette.TextStrong
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).drawBehind {
            val x = RailX.toPx(); val y = NodeY.toPx(); val w = 3.dp.toPx()
            if (!first && line.value > 0f) drawLine(RailPassed, Offset(x, 0f), Offset(x, y * line.value), w, StrokeCap.Round)
            if (tail.value > 0f) drawLine(RailPassed, Offset(x, y), Offset(x, y + (size.height - y) * tail.value), w, StrokeCap.Round)
        },
    ) {
        Box(Modifier.width(RailX * 2), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier.padding(top = NodeY - 13.dp).size(26.dp)
                    .graphicsLayer { scaleX = dot.value; scaleY = dot.value; alpha = dot.value.coerceIn(0f, 1f) }
                    .clip(CircleShape).background(ink),
                contentAlignment = Alignment.Center,
            ) { Text("$number", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }
        Column(Modifier.weight(1f).padding(start = 10.dp, bottom = 8.dp)) {
            Column(Modifier.graphicsLayer { alpha = body.value; translationY = (1f - body.value) * 18.dp.toPx() }) {
                Text(timeLine(r), fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 3.dp))
                Text(r.place.name.ifBlank { "이름 없는 곳" }, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.padding(top = 2.dp))
                if (r.unplanned) Text("계획에 없던 곳", fontSize = 14.sp, color = Palette.TextSoft)
            }
            Column(Modifier.graphicsLayer { alpha = extra.value; translationY = (1f - extra.value) * 24.dp.toPx() }) {
                if (r.photoUrls.isNotEmpty()) {
                    coil3.compose.AsyncImage(
                        model = r.photoUrls.first(), contentDescription = "사진 크게 보기", contentScale = ContentScale.Crop,
                        modifier = Modifier.padding(top = 12.dp).fillMaxWidth().aspectRatio(4f / 3f).clip(Corner).clickable { onPhoto(0) },
                    )
                    if (r.photoUrls.size > 1) LazyRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(r.photoUrls.drop(1)) { j, url ->
                            coil3.compose.AsyncImage(model = url, contentDescription = "사진 크게 보기", contentScale = ContentScale.Crop,
                                modifier = Modifier.size(88.dp).clip(Corner).clickable { onPhoto(j + 1) })
                        }
                    }
                }
                if (r.note.isNotBlank()) Text("“${r.note}”", fontSize = 19.sp, color = Palette.TextStrong, lineHeight = 27.sp, modifier = Modifier.padding(top = 12.dp))
                said.forEach { c ->
                    Row(Modifier.padding(top = 8.dp).speechBubble().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(nameOf(c.uid), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
                        Text("  ${c.text}", fontSize = 16.sp, color = Palette.TextStrong)
                    }
                }
                val small = buildList {
                    if (r.parkingNote.isNotBlank()) add("주차 ${r.parkingNote}")
                    if (r.againBy.isNotEmpty()) add("또 갈래요 · " + r.againBy.map(nameOf).joinToString("·"))
                }
                if (small.isNotEmpty()) Text(small.joinToString("   "), fontSize = 15.sp, color = Palette.ChipText, modifier = Modifier.padding(top = 10.dp))
            }
            // The drive to the next place, on the rail as it heads on.
            val move = moveLine(r, next)
            Text(move ?: "", fontSize = 14.sp, color = Palette.TextSoft,
                modifier = Modifier.graphicsLayer { alpha = tail.value }.padding(top = 22.dp, bottom = 18.dp))
        }
    }
}

@Composable
private fun StoryEnd(records: List<Record>, state: LazyListState, itemIndex: Int, seen: MutableSet<Int>, finished: androidx.compose.runtime.MutableIntState, next: DiaryDay?, onNext: () -> Unit, onEdit: () -> Unit) {
    val done = itemIndex in seen
    val a = remember { Animatable(if (done) 1f else 0f) }
    val show = myTurn(state, itemIndex, finished).first
    LaunchedEffect(show) { if (show && itemIndex !in seen) { seen += itemIndex; a.animateTo(1f, tween(300)) } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 18.dp.toPx() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(RailX * 2), contentAlignment = Alignment.Center) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(RailPassed))
            }
            val first = records.firstNotNullOfOrNull { it.arrivedAt }
            val last = records.lastOrNull()?.let { it.leftAt ?: it.arrivedAt }
            val span = if (first != null && last != null) hours(last.toDate().time - first.toDate().time) else null
            Text(if (records.isEmpty()) "아직 남긴 곳이 없어요" else "하루 끝 · ${records.map { it.place.name }.distinct().size}곳" + (span?.let { " · $it" } ?: ""),
                fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.padding(start = 10.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEdit, shape = Corner, modifier = Modifier.weight(1f).height(52.dp)) {
                Text("이어 쓰기 · 사진 넣기", fontSize = 16.sp, color = Palette.TextStrong)
            }
            if (next != null) Button(onClick = onNext, shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy), modifier = Modifier.weight(1f).height(52.dp)) {
                Text("${next.dayNo}일차 넘기기", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun clock(ms: Long): String {
    val t = java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime()
    return "%02d:%02d".format(t.hour, t.minute)
}

private fun hours(ms: Long): String? {
    val m = ms / 60_000
    return when {
        m < 1 -> null
        m < 60 -> "${m}분"
        m % 60 == 0L -> "${m / 60}시간"
        else -> "${m / 60}시간 ${m % 60}분"
    }
}

/** "10:02 도착 · 1시간 10분 머묾". */
private fun timeLine(r: Record): String {
    val a = r.arrivedAt?.toDate()?.time ?: return "들른 곳"
    val stay = r.leftAt?.toDate()?.time?.let { hours(it - a) }
    return clock(a) + " 도착" + (stay?.let { " · $it 머묾" } ?: "")
}

/** "이동 25분" between leaving here and reaching the next place. */
private fun moveLine(r: Record, next: Record?): String? {
    next ?: return null
    val from = (r.leftAt ?: r.arrivedAt)?.toDate()?.time ?: return null
    val to = next.arrivedAt?.toDate()?.time ?: return null
    return hours(to - from)?.let { "이동 $it" }
}
