package com.htb.carplan.phone.record

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * 14장 기록 탭 — 읽는 일기 한 장 (정원 2026-11-04: "바로 전체가 보이는 게 아니라 스크롤할수록 하나씩 이어지게").
 * Scrollytelling, like Polarsteps' step pages or the scroll-drawn timelines on the web: **the scroll is the clock**.
 * How far a place has come up the screen decides how much of it is drawn — the rail grows down to it, the dot lands
 * (with a tick), the place and time, then the big photo, the line someone wrote and the comments, then the rail heads
 * on toward the next place. Scroll back and it rewinds. Each place takes most of a screen, so a day is read one place
 * at a time. The map shows the route read so far and the place being read: beside the story on a wide screen, at the
 * top on a phone.
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
    var viewing by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    val d = LocalDate.parse(page.date)
    val index = book.pages.indexOfFirst { it.first.date == page.date }
    val prev = book.pages.getOrNull(index - 1)?.first
    val next = book.pages.getOrNull(index + 1)?.first
    val nameOf: (String?) -> String = { uid -> if (uid == vm.me()) "나" else names[uid]?.ifBlank { null } ?: "일행" }
    val pts = records.filter { it.place.lat != 0.0 || it.place.lng != 0.0 }
    // The place being read now: the last one whose dot has landed (list item i + 1 = record i).
    val current by remember(state, records) {
        derivedStateOf { records.indices.lastOrNull { progressNow(state, it + 1) > DOT_AT } ?: -1 }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Background).statusBarsPadding()) {
        val wide = maxWidth >= 840.dp
        val viewH = maxHeight
        Column(Modifier.fillMaxSize()) {
            TopBar(page, d, book, prev, next, state, onBack, onPage)
            Row(Modifier.fillMaxSize()) {
                if (wide && pts.isNotEmpty()) {
                    Box(Modifier.weight(1f).fillMaxHeight().padding(start = 20.dp, top = 8.dp, bottom = 20.dp)) {
                        RouteMap(pts, records, current, Modifier.fillMaxSize().clip(Corner))
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = state, horizontalAlignment = Alignment.CenterHorizontally) {
                    item(key = "head") {
                        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 20.dp)) {
                            Header(page, d, book, records, names, vm)
                            if (!wide && pts.isNotEmpty()) {
                                Spacer(Modifier.height(16.dp))
                                RouteMap(pts, records, current, Modifier.fillMaxWidth().height(200.dp).clip(Corner))
                            }
                            Spacer(Modifier.height(28.dp))
                        }
                    }
                    itemsIndexed(records, key = { _, r -> r.id.ifBlank { r.place.name } }) { i, r ->
                        StoryStop(
                            number = i + 1, r = r, next = records.getOrNull(i + 1), first = i == 0,
                            said = comments.filter { r.stopId != null && it.stopId == r.stopId }, nameOf = nameOf,
                            progress = rememberProgress(state, i + 1), minHeight = viewH * 0.62f,
                            onPhoto = { at -> viewing = r.photoUrls to at },
                        )
                    }
                    item(key = "end") {
                        StoryEnd(records, rememberProgress(state, records.size + 1), next, onNext = { next?.let(onPage) }, onEdit = onEdit)
                        // Room under the end, so the last places can come all the way up the screen.
                        Spacer(Modifier.height(viewH * 0.45f))
                    }
                }
            }
        }
    }
    viewing?.let { (urls, at) -> PhotoViewer(urls, at) { viewing = null } }
}

/** Pinned: back, and once the big date has scrolled away, the date and title in one line; 일차 ‹ ›. */
@Composable
private fun TopBar(page: DiaryDay, d: LocalDate, book: DiaryBook, prev: DiaryDay?, next: DiaryDay?, state: LazyListState, onBack: () -> Unit, onPage: (DiaryDay) -> Unit) {
    val small by remember(state) { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 160 } }
    val a by animateFloatAsState(if (small) 1f else 0f, label = "topTitle")
    Column {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = Palette.TextStrong) }
            Text("${d.monthValue}월 ${d.dayOfMonth}일 · " + (if (page.isLog) "일상" else page.title.ifBlank { "여행" }),
                fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, maxLines = 1,
                modifier = Modifier.weight(1f).graphicsLayer { alpha = a })
            if (book.pages.size > 1) {
                Text("‹", fontSize = 26.sp, color = if (prev != null) Palette.TextStrong else Palette.Line,
                    modifier = Modifier.clip(Corner).clickable(enabled = prev != null) { prev?.let(onPage) }.padding(horizontal = 14.dp, vertical = 4.dp))
                Text("${page.dayNo}일차", fontSize = 15.sp, color = Palette.TextSoft)
                Text("›", fontSize = 26.sp, color = if (next != null) Palette.TextStrong else Palette.Line,
                    modifier = Modifier.clip(Corner).clickable(enabled = next != null) { next?.let(onPage) }.padding(horizontal = 14.dp, vertical = 4.dp))
            }
        }
        HorizontalDivider(color = Palette.Line.copy(alpha = a))
    }
}

@Composable
private fun Header(page: DiaryDay, d: LocalDate, book: DiaryBook, records: List<Record>, names: Map<String, String>, vm: RecordTabViewModel) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
        Text("${d.dayOfMonth}", fontSize = 64.sp, fontWeight = FontWeight.Black, color = Palette.TextStrong, lineHeight = 64.sp)
        Column(Modifier.padding(start = 12.dp, bottom = 10.dp)) {
            Text("${d.year}년 ${d.monthValue}월", fontSize = 15.sp, color = Palette.TextSoft)
            Text(d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.KOREAN), fontSize = 17.sp, color = Palette.TextStrong)
        }
    }
    Text(if (page.isLog) "일상" else page.title.ifBlank { "여행" }, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.padding(top = 12.dp))
    val with = book.memberIds.filter { it != vm.me() }.map { names[it]?.ifBlank { null } ?: "일행" }.distinct()
    val photos = records.sumOf { it.photoUrls.size }
    Text(buildList {
        if (book.totalDays > 1) add("${page.dayNo}일차")
        add("${records.map { it.place.name }.distinct().size}곳")
        if (photos > 0) add("사진 ${photos}장")
        if (with.isNotEmpty()) add("함께 · " + with.joinToString("·"))
    }.joinToString("  ·  "), fontSize = 15.sp, color = Palette.ChipText, modifier = Modifier.padding(top = 4.dp))
    Text("아래로 내리며 하루를 따라가 보세요", fontSize = 14.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 18.dp))
}

/** The day's route: grey all the way, blue as far as you've read, the place being read in ink. */
@Composable
private fun RouteMap(pts: List<Record>, records: List<Record>, current: Int, modifier: Modifier) {
    val all = pts.map { it.place.lat to it.place.lng }
    val upTo = if (current < 0) 0 else pts.count { records.indexOf(it) <= current }
    SimpleMap(
        centerLat = all.first().first, centerLng = all.first().second, zoom = 13,
        markers = pts.mapIndexed { i, r ->
            MapMarker(r.place.lat, r.place.lng, if (i < upTo) "${records.indexOf(r) + 1}" else "",
                if (records.indexOf(r) == current) MapMarker.Kind.PICKUP else MapMarker.Kind.STOP)
        },
        passed = all,
        path = all.take(upTo),
        fit = if (all.size >= 2) all else emptyList(),
        modifier = modifier,
    )
}

private val RailX = 18.dp
private val NodeY = 14.dp
private const val DOT_AT = 0.16f

/** 0 = the place is still below the screen, 1 = it has come all the way up; between = how much of it is drawn. */
private fun progressNow(state: LazyListState, itemIndex: Int): Float {
    val info = state.layoutInfo
    val it = info.visibleItemsInfo.firstOrNull { it.index == itemIndex }
        ?: return if (itemIndex < state.firstVisibleItemIndex) 1f else 0f
    val start = info.viewportEndOffset * 0.92f          // starts when its top comes onto the lower screen
    val span = minOf(it.size.toFloat(), info.viewportEndOffset * 0.62f)
    return ((start - it.offset) / span).coerceIn(0f, 1f)
}

@Composable
private fun rememberProgress(state: LazyListState, itemIndex: Int): State<Float> =
    remember(state, itemIndex) { derivedStateOf { progressNow(state, itemIndex) } }

/** Part [from]..[to] of the progress, as 0..1. */
private fun seg(p: Float, from: Float, to: Float) = ((p - from) / (to - from)).coerceIn(0f, 1f)

@Composable
private fun StoryStop(
    number: Int, r: Record, next: Record?, first: Boolean, said: List<Comment>, nameOf: (String?) -> String,
    progress: State<Float>, minHeight: Dp, onPhoto: (Int) -> Unit,
) {
    // The dot doesn't scrub: it lands with a little bounce (and a tick) once its part of the scroll is reached.
    val landed by remember { derivedStateOf { progress.value > DOT_AT } }
    val dot by animateFloatAsState(if (landed) 1f else 0f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow), label = "dot")
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(landed) { if (landed) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val ink = Palette.TextStrong
    Row(
        Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(min = minHeight).padding(horizontal = 20.dp).drawBehind {
            val p = progress.value
            val x = RailX.toPx(); val y = NodeY.toPx(); val w = 3.dp.toPx()
            val top = seg(p, 0f, DOT_AT)
            if (!first && top > 0f) drawLine(RailPassed, Offset(x, 0f), Offset(x, y * top), w, StrokeCap.Round)
            val tail = seg(p, 0.62f, 1f)
            if (tail > 0f) drawLine(RailPassed, Offset(x, y), Offset(x, y + (size.height - y) * tail), w, StrokeCap.Round)
        },
    ) {
        Box(Modifier.width(RailX * 2), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier.padding(top = NodeY - 13.dp).size(26.dp)
                    .graphicsLayer { scaleX = dot; scaleY = dot; alpha = dot.coerceIn(0f, 1f) }
                    .clip(CircleShape).background(ink),
                contentAlignment = Alignment.Center,
            ) { Text("$number", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Column(Modifier.graphicsLayer { val b = seg(progress.value, 0.14f, 0.34f); alpha = b; translationY = (1f - b) * 22.dp.toPx() }) {
                Text(timeLine(r), fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 3.dp))
                Text(r.place.name.ifBlank { "이름 없는 곳" }, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.padding(top = 2.dp))
                if (r.place.address.isNotBlank()) Text(r.place.address, fontSize = 14.sp, color = Palette.TextSoft, maxLines = 1)
                if (r.unplanned) Text("계획에 없던 곳", fontSize = 14.sp, color = Palette.TextSoft)
            }
            if (r.photoUrls.isNotEmpty()) {
                // The photo rises a little slower than the words (parallax), then settles.
                Column(Modifier.graphicsLayer { val e = seg(progress.value, 0.26f, 0.52f); alpha = e; translationY = (1f - e) * 48.dp.toPx(); scaleX = 0.96f + 0.04f * e; scaleY = scaleX }) {
                    coil3.compose.AsyncImage(
                        model = r.photoUrls.first(), contentDescription = "사진 크게 보기", contentScale = ContentScale.Crop,
                        modifier = Modifier.padding(top = 14.dp).fillMaxWidth().aspectRatio(4f / 3f).clip(Corner).clickable { onPhoto(0) },
                    )
                    if (r.photoUrls.size > 1) LazyRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(r.photoUrls.drop(1)) { j, url ->
                            coil3.compose.AsyncImage(model = url, contentDescription = "사진 크게 보기", contentScale = ContentScale.Crop,
                                modifier = Modifier.size(96.dp).clip(Corner).clickable { onPhoto(j + 1) })
                        }
                    }
                }
            }
            Column(Modifier.graphicsLayer { val e = seg(progress.value, 0.4f, 0.62f); alpha = e; translationY = (1f - e) * 26.dp.toPx() }) {
                if (r.note.isNotBlank()) Text("“${r.note}”", fontSize = 20.sp, color = Palette.TextStrong, lineHeight = 29.sp, modifier = Modifier.padding(top = 14.dp))
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
            Text(moveLine(r, next) ?: "", fontSize = 14.sp, color = Palette.TextSoft,
                modifier = Modifier.graphicsLayer { alpha = seg(progress.value, 0.7f, 1f) }.padding(top = 28.dp, bottom = 28.dp))
        }
    }
}

@Composable
private fun StoryEnd(records: List<Record>, progress: State<Float>, next: DiaryDay?, onNext: () -> Unit, onEdit: () -> Unit) {
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 20.dp).graphicsLayer {
        val a = seg(progress.value, 0f, 0.3f); alpha = a; translationY = (1f - a) * 18.dp.toPx()
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(RailX * 2), contentAlignment = Alignment.Center) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(RailPassed))
            }
            val first = records.firstNotNullOfOrNull { it.arrivedAt }
            val last = records.lastOrNull()?.let { it.leftAt ?: it.arrivedAt }
            val span = if (first != null && last != null) hours(last.toDate().time - first.toDate().time) else null
            Text(if (records.isEmpty()) "아직 남긴 곳이 없어요" else "하루 끝 · ${records.map { it.place.name }.distinct().size}곳" + (span?.let { " · $it" } ?: ""),
                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.padding(start = 10.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
