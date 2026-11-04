package com.htb.carplan.car

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htb.carplan.shared.calendar.MaintenanceRules
import com.htb.carplan.shared.model.MaintenanceItem
import com.htb.carplan.shared.model.Record
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlinx.coroutines.launch

private val Corner = RoundedCornerShape(4.dp)

private fun dayText(d: LocalDate) = "${d.monthValue}월 ${d.dayOfMonth}일 (${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})"

/**
 * 기록 탭 (⑤, 정원 2026-10-31: 날짜 목록 대신 캘린더 + 오늘 화면 같은 타임라인). Left (half screen: top) the month —
 * days the car stopped are marked with a bar per trip (일상 기록 grey). Right (below) the chosen day as a rail: when
 * the car stood where, how long, the line written, parking, photos, and the drive between. Reading only: photos and
 * lines are added on the phone.
 */
@Composable
fun RowScope.CarRecords(vm: CarHomeViewModel) {
    val c = LocalCarColors.current
    val days by vm.recordDays.collectAsStateWithLifecycle()
    val compact = LocalCompact.current
    val byDate = remember(days) { days.groupBy { it.date } }
    var month by remember { mutableStateOf(java.time.YearMonth.now()) }
    // Opens on the newest day with records (not an empty today).
    var picked by remember { mutableStateOf<LocalDate?>(null) }
    val shown = picked ?: days.firstOrNull()?.date ?: LocalDate.now()
    OnCarTabTapAgain { picked = null; month = days.firstOrNull()?.date?.let { java.time.YearMonth.from(it) } ?: java.time.YearMonth.now() }
    androidx.compose.runtime.LaunchedEffect(days.firstOrNull()?.date) { if (picked == null) days.firstOrNull()?.date?.let { month = java.time.YearMonth.from(it) } }
    val cal: @Composable (Modifier) -> Unit = { m -> RecordMonth(vm::coverOf, month, { month = it }, byDate, shown, { picked = it }, m) }
    val line: @Composable (Modifier) -> Unit = { m ->
        androidx.compose.animation.Crossfade(shown, m, animationSpec = carSpec(), label = "recordDay") { d -> DayTimeline(vm, d, byDate[d].orEmpty()) }
    }
    if (!compact) {
        cal(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 28.dp, vertical = 20.dp))
        VerticalDivider(color = c.line)
        line(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 28.dp, vertical = 20.dp))
    } else androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
        // Half screen (정원 2026-10-31): month on top, the day below — pushing the day up folds the month away
        // (it slides up out of sight) so the records get the whole height; pulling down at the top brings it back.
        val full = maxHeight * 0.48f
        val fullPx = with(androidx.compose.ui.platform.LocalDensity.current) { full.toPx() }
        var frac by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
        // Which way the last push went: on release the month finishes going that way.
        var lastDir by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
        val uiScope = androidx.compose.runtime.rememberCoroutineScope()
        val motion = LocalMotion.current
        fun settle(to: Float) { uiScope.launch { if (motion) androidx.compose.animation.core.animate(frac, to, animationSpec = tween(MOTION_MS)) { v, _ -> frac = v } else frac = to } }
        val fold = remember(fullPx) {
            object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                    if (available.y >= 0 || frac <= 0f) return androidx.compose.ui.geometry.Offset.Zero
                    lastDir = -1f
                    val before = frac
                    frac = (frac + available.y / fullPx).coerceIn(0f, 1f)
                    return androidx.compose.ui.geometry.Offset(0f, (frac - before) * fullPx)
                }
                override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                    if (available.y <= 0 || frac >= 1f) return androidx.compose.ui.geometry.Offset.Zero
                    lastDir = 1f
                    val before = frac
                    frac = (frac + available.y / fullPx).coerceIn(0f, 1f)
                    return androidx.compose.ui.geometry.Offset(0f, (frac - before) * fullPx)
                }
                // Called when the finger lifts (with or without a fling): never left half-open.
                override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                    if (frac > 0f && frac < 1f) settle(if (lastDir < 0f) 0f else if (lastDir > 0f) 1f else if (frac < 0.5f) 0f else 1f)
                    return androidx.compose.ui.unit.Velocity.Zero
                }
            }
        }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(full * frac).clipToBounds()) {
                // Bottom-anchored: as the box shrinks the month leaves upward.
                cal(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Bottom, unbounded = true).height(full).padding(horizontal = 20.dp, vertical = 12.dp))
            }
            // The handle: one tap folds or unfolds, for when swiping is not what the driver does.
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { settle(if (frac > 0.5f) 0f else 1f) }.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).height(1.dp).background(c.line))
                Text(if (frac > 0.5f) "  달력 접기 ▴  " else "  ${month.monthValue}월 달력 ▾  ", color = c.soft, fontSize = 20.sp)
                Box(Modifier.weight(1f).height(1.dp).background(c.line))
            }
            Box(Modifier.weight(1f).fillMaxWidth().nestedScroll(fold)) { line(Modifier.fillMaxSize().padding(horizontal = 20.dp)) }
        }
    }
}

/** The month with the days that have records marked (one bar per trip; 일상 기록 grey). */
@Composable
private fun RecordMonth(coverOf: suspend (RecordDay) -> String?, month: java.time.YearMonth, onMonth: (java.time.YearMonth) -> Unit, byDate: Map<LocalDate, List<RecordDay>>, picked: LocalDate, onPick: (LocalDate) -> Unit, modifier: Modifier) {
    val c = LocalCarColors.current
    val compact = LocalCompact.current
    val today = LocalDate.now()
    // No months after this one (nothing recorded yet), same as the › button.
    Column(modifier.monthSwipe(month, onPrev = { onMonth(month.minusMonths(1)) }, onNext = { if (month < java.time.YearMonth.from(today)) onMonth(month.plusMonths(1)) })) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onMonth(month.minusMonths(1)) }, shape = Corner, border = BorderStroke(1.dp, c.line), modifier = Modifier.heightIn(min = 76.dp).width(96.dp)) { Text("‹", color = c.ink, fontSize = 28.sp) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${month.monthValue}월 기록", color = c.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            OutlinedButton(onClick = { onMonth(month.plusMonths(1)) }, enabled = month < java.time.YearMonth.from(today), shape = Corner, border = BorderStroke(1.dp, c.line), modifier = Modifier.heightIn(min = 76.dp).width(96.dp)) { Text("›", color = c.ink, fontSize = 28.sp) }
        }
        Row(Modifier.padding(top = 10.dp)) {
            listOf("일", "월", "화", "수", "목", "금", "토").forEach { d -> Text(d, color = c.soft, fontSize = 20.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        HorizontalDivider(color = c.line, modifier = Modifier.padding(top = 6.dp))
        val lead = month.atDay(1).dayOfWeek.value % 7
        val n = month.lengthOfMonth()
        val rows = (lead + n + 6) / 7
        val trip = androidx.compose.ui.graphics.Color(com.htb.carplan.shared.calendar.CategoryColors.of(com.htb.carplan.shared.model.BuiltInCategory.TRIP.id, emptyList()))
        Column(Modifier.weight(1f)) {
            for (r in 0 until rows) Row(Modifier.weight(1f)) {
                for (col in 0 until 7) {
                    val dn = r * 7 + col - lead + 1
                    Box(Modifier.weight(1f).fillMaxHeight().padding(2.dp)) {
                        if (dn in 1..n) {
                            val d = month.atDay(dn)
                            val here = byDate[d].orEmpty()
                            val on = d == picked
                            // The chosen day is framed, not filled (디자인 평가 2026-11-04: an ink fill hid the day's trip name).
                            val bg = c.bg
                            val fg by carColor(if (here.isEmpty()) c.soft else c.ink, "recText")
                            // An album: a day with a photo shows it; the chosen day gets a thick ink frame.
                            val cover by androidx.compose.runtime.produceState<String?>(null, here) { value = here.firstNotNullOfOrNull { coverOf(it) } }
                            if (cover != null) Photo(cover!!, Modifier.fillMaxSize())
                            Column(
                                Modifier.fillMaxSize().background(if (cover != null && !on) androidx.compose.ui.graphics.Color.Transparent else bg, Corner)
                                    .then(if (on) Modifier.border(4.dp, c.ink, Corner) else if (d == today) Modifier.border(2.dp, c.line, Corner) else Modifier)
                                    .clickable(enabled = here.isNotEmpty()) { onPick(d) }.padding(if (compact) 4.dp else 6.dp),
                            ) {
                                // On a photo the number sits on a small white tab so it stays readable.
                                Text("$dn", color = if (cover != null) c.ink else fg, fontSize = 24.sp, fontWeight = if (here.isNotEmpty()) FontWeight.Bold else FontWeight.Normal,
                                    modifier = if (cover != null) Modifier.background(c.bg, Corner).padding(horizontal = 6.dp) else Modifier)
                                if (cover == null) here.take(2).forEach { rd ->
                                    val color = if (rd.eventId.startsWith("log-")) c.soft else trip
                                    if (compact) Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(6.dp).background(color))
                                    else Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.width(8.dp).height(24.dp).background(color))
                                        Text(rd.title, color = fg, fontSize = 20.sp, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Clip, modifier = Modifier.padding(start = 6.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One day on the rail, like the 오늘 timeline (정원 2026-10-31: 타임라인 모양으로, 감상이 주인공). Each stop is a ✓
 * node with its time on the left; beside it the photos taken there, the line written in large type and the group's
 * comments as named bubbles — the facts (stay, parking) small underneath, the drive to the next stop between.
 */
@Composable
private fun DayTimeline(vm: CarHomeViewModel, date: LocalDate, rds: List<RecordDay>) {
    val c = LocalCarColors.current
    val loaded = rds.map { rd ->
        val recs by remember(rd) { vm.recordsOf(rd) }.collectAsState(initial = null)
        val comments by remember(rd) { vm.dayCommentsOf(rd) }.collectAsState(initial = emptyMap())
        Triple(rd, recs, comments)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (rds.isEmpty()) {
            Text(dayText(date), color = c.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text("이 날은 기록이 없어요 · 표시된 날을 눌러요", color = c.soft, fontSize = 24.sp, modifier = Modifier.padding(top = 12.dp))
        }
        // A trip of that day with no records yet (today's other trip) stays out when another one has some.
        val anyRecords = loaded.any { !it.second.isNullOrEmpty() }
        loaded.filter { !anyRecords || !it.second.isNullOrEmpty() }.forEachIndexed { ti, (rd, recs, comments) ->
            if (ti > 0) HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 20.dp))
            val list = recs.orEmpty()
            // A diary page, like the phone's (디자인 평가 2026-11-04): the big date first, then the trip.
            if (ti == 0) Row(verticalAlignment = Alignment.Bottom) {
                Text("${date.dayOfMonth}", color = c.ink, fontSize = 64.sp, fontWeight = FontWeight.Black, lineHeight = 64.sp)
                Column(Modifier.padding(start = 14.dp, bottom = 8.dp)) {
                    Text("${date.year}년 ${date.monthValue}월", color = c.soft, fontSize = 20.sp)
                    Text(date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN), color = c.ink, fontSize = 24.sp)
                }
            }
            Text(if (rd.eventId.startsWith("log-")) "일상" else rd.title, color = c.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(top = 10.dp))
            Text(
                listOfNotNull(list.map { it.place.name }.distinct().size.takeIf { it > 0 }?.let { "${it}곳" }, list.sumOf { it.photoUrls.size }.takeIf { it > 0 }?.let { "사진 ${it}장" }).joinToString("  ·  ").ifBlank { dayText(date) },
                color = c.soft, fontSize = 20.sp, modifier = Modifier.padding(bottom = 20.dp),
            )
            when {
                recs == null -> Text("불러오는 중", color = c.soft, fontSize = 24.sp)
                list.isEmpty() -> Text("이 여행엔 남긴 곳이 아직 없어요", color = c.soft, fontSize = 24.sp)
                else -> {
                    list.forEachIndexed { i, r -> RailEntry(r, list.getOrNull(i + 1), first = i == 0, comments = comments[r.stopId].orEmpty(), number = i + 1) }
                    val start = list.firstNotNullOfOrNull { it.arrivedAt }?.toDate()?.time
                    val end = list.last().let { it.leftAt ?: it.arrivedAt }?.toDate()?.time
                    val span = if (start != null && end != null && end - start >= 60_000) ((end - start) / 60_000).let { m -> if (m >= 60) "${m / 60}시간${if (m % 60 > 0) " ${m % 60}분" else ""}" else "${m}분" } else null
                    Text(listOfNotNull("하루 끝", "${list.map { it.place.name }.distinct().size}곳", span).joinToString(" · "),
                        color = c.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 48.dp, top = 4.dp))
                }
            }
        }
        Text("사진·한 줄은 폰 기록 탭에서 남겨요", color = c.soft, fontSize = 20.sp, modifier = Modifier.padding(top = 28.dp, bottom = 12.dp))
    }
}

/** A stop on the rail with what was left there: photos, my line (big), the group's comments; the facts small. */
@Composable
private fun RailEntry(r: Record, next: Record?, first: Boolean, comments: List<StopComment>, number: Int) {
    val c = LocalCarColors.current
    val hm = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }
    val arrived = r.arrivedAt?.toDate()
    val left = r.leftAt?.toDate()
    val passed = c.railPassed
    val rich = r.photoUrls.isNotEmpty() || r.note.isNotBlank() || comments.isNotEmpty()
    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        Box(
            Modifier.width(48.dp).fillMaxHeight().drawBehind {
                val x = size.width / 2
                val cy = 20.dp.toPx()
                val w = 4.dp.toPx()
                if (!first) drawLine(passed, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, cy), w)
                if (next != null) drawLine(passed, androidx.compose.ui.geometry.Offset(x, cy), androidx.compose.ui.geometry.Offset(x, size.height), w)
            },
            contentAlignment = Alignment.TopCenter,
        ) {
            // Numbered like the phone diary: the order of the day, not just "done".
            Box(Modifier.padding(top = 4.dp).size(34.dp).clip(CircleShape).background(c.ink), contentAlignment = Alignment.Center) {
                Text("$number", color = c.onInk, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        // Time on the left, small — the moment matters less than what was left there.
        Text(arrived?.let(hm::format) ?: "", color = c.soft, fontSize = 24.sp, modifier = Modifier.width(88.dp).padding(top = 4.dp))
        Column(Modifier.weight(1f).padding(bottom = if (next != null) 24.dp else 8.dp)) {
            Text(r.place.name.ifBlank { "이름 없는 곳" }, color = c.ink, fontSize = if (rich) 30.sp else 26.sp, fontWeight = if (rich) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
            when (r.photoUrls.size) {
                0 -> {}
                1 -> Photo(r.photoUrls[0], Modifier.padding(top = 10.dp).fillMaxWidth().height(220.dp))
                else -> r.photoUrls.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { Photo(it, Modifier.weight(1f).height(160.dp)) }
                        if (row.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
            if (r.note.isNotBlank()) Text(r.note, color = c.ink, fontSize = 28.sp, lineHeight = 40.sp, modifier = Modifier.padding(top = 10.dp))
            comments.forEach { cm ->
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                    Text(cm.who, color = c.soft, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp).padding(top = 8.dp))
                    Text(cm.text, color = c.ink, fontSize = 24.sp, modifier = Modifier.border(1.dp, c.line, Corner).padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            listOfNotNull(
                if (arrived != null && left != null) "${((left.time - arrived.time) / 60_000).coerceAtLeast(1)}분 머묾" else null,
                r.parkingNote.ifBlank { null }?.let { "주차 $it" },
                if (r.unplanned) "계획 밖" else null,
            ).joinToString("  ·  ").takeIf { it.isNotEmpty() }?.let { Text(it, color = c.soft, fontSize = 20.sp, modifier = Modifier.padding(top = 8.dp)) }
            val nextAt = next?.arrivedAt?.toDate()
            if (left != null && nextAt != null && nextAt.after(left)) Text("이동 ${((nextAt.time - left.time) / 60_000).coerceAtLeast(1)}분", color = c.soft, fontSize = 20.sp, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

/** A record photo from Storage, cropped to its frame; the frame is grey while it loads. */
@Composable
private fun Photo(url: String, modifier: Modifier) {
    val c = LocalCarColors.current
    coil3.compose.AsyncImage(
        model = url, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = modifier.clip(Corner).background(c.sheet),
    )
}

/**
 * 차량 탭 (⑤): what the car says about itself (fuel, range, odometer, this month's fuel money) and each 차량 관리
 * item — when it is due, and [했어요] to log it today at the car's own odometer. Amounts and intervals: phone.
 */
@Composable
fun RowScope.CarVehicle(vm: CarHomeViewModel) {
    val c = LocalCarColors.current
    val compact = LocalCompact.current
    val v by vm.vehicle.collectAsStateWithLifecycle()
    val car by vm.car.collectAsStateWithLifecycle()
    val events by vm.careEvents.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val records = events.filter { !it.id.startsWith("due-") }
    val dues = events.filter { it.id.startsWith("due-") }.associateBy { it.maintenance?.item }
    val monthFuel = records.filter { it.maintenance?.item == MaintenanceItem.FUEL && it.startDate.startsWith(today.toString().take(7)) }.sumOf { it.maintenance?.amountWon ?: 0L }
    Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = if (compact) 20.dp else 40.dp, vertical = 20.dp)) {
        val stats = listOf(
            (if (v.isEv == true) "배터리" else "연료") to (v.fuelPercent?.let { "${it.toInt()}%" } ?: "—"),
            "주행가능" to (v.rangeKm?.takeIf { it > 0 }?.let { "${it.toInt()}km" } ?: "—"),
            "주행거리" to (v.odometerKm?.let { "%,dkm".format(it.toLong()) } ?: "—"),
            "이번 달 주유비" to "%,d원".format(monthFuel),
        )
        // Wide: four in a row. Half screen: two rows of two.
        stats.chunked(if (compact) 2 else 4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, color = c.soft, fontSize = 20.sp)
                        Text(value, color = c.ink, fontSize = 36.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
        HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("차량 관리", color = c.ink, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("금액·주기는 폰 차량 탭에서", color = c.soft, fontSize = 20.sp)
        }
        // What the car itself warns about now (Pleos Safety/Tire), first and in the warning colour.
        val warnings = v.warnings.mapNotNull { runCatching { MaintenanceItem.valueOf(it) }.getOrNull() }
        warnings.forEach { w ->
            Text("차 알림 · " + MaintenanceRules.warningText(w), color = c.warn, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        }
        if (v.tireKpa.size == 4) Text(
            "공기압  앞 " + "${v.tireKpa["FL"]?.toInt()} · ${v.tireKpa["FR"]?.toInt()}  뒤 ${v.tireKpa["RL"]?.toInt()} · ${v.tireKpa["RR"]?.toInt()} kPa",
            color = c.soft, fontSize = 20.sp, modifier = Modifier.padding(top = 4.dp),
        )
        LazyColumn(Modifier.weight(1f)) {
            // Due soonest first; an EV hides engine parts; washer fluid only when the car says it is low.
            val shown = MaintenanceItem.entries.filter { item ->
                (item != MaintenanceItem.INSURANCE || dues[item] != null) && !(item.engineOnly && v.isEv == true) &&
                    (item != MaintenanceItem.WASHER_FLUID || item in warnings) && item != MaintenanceItem.FUEL
            }.sortedBy { dues[it]?.startDate ?: "9999" }
            items(listOf(MaintenanceItem.FUEL) + shown, key = { it.name }) { item ->
                val last = records.firstOrNull { it.maintenance?.item == item }
                val due = dues[item]
                val dueDays = due?.let { ChronoUnit.DAYS.between(today, LocalDate.parse(it.startDate)) }
                Row(Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.label, color = c.ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                        Text(last?.let { "마지막 ${it.startDate.substring(5).replace('-', '/')}" + (it.maintenance?.odometerKm?.let { km -> " · ${"%,d".format(km.toLong())}km" } ?: "") }
                            ?: MaintenanceRules.NOTES[item]?.let { "추천 · $it" } ?: "기록 없음", color = c.soft, fontSize = 20.sp, maxLines = 2)
                    }
                    // How soon, in a word: only overdue / today in the warning colour (one accent rule).
                    val (soon, warn) = when {
                        item.name in v.warnings -> "지금" to true
                        dueDays == null -> MaintenanceRules.interval(item, car?.intervals.orEmpty()).let { i -> listOfNotNull(i.km?.let { "${"%,d".format(it)}km" }, i.days?.let { "${it}일" }).joinToString("·").let { if (it.isEmpty()) "—" else "주기 $it" } } to false
                        dueDays < 0 -> "${-dueDays}일 지남" to true
                        dueDays == 0L -> "오늘" to true
                        else -> "${dueDays}일 남음" to false
                    }
                    Text(soon, color = if (warn) c.warn else c.ink, fontSize = 24.sp, fontWeight = if (warn) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(horizontal = 16.dp))
                    if (item != MaintenanceItem.INSURANCE) OutlinedButton(
                        onClick = { vm.logCare(item) }, shape = Corner, border = BorderStroke(1.dp, c.line),
                        modifier = Modifier.heightIn(min = 76.dp).width(if (compact) 120.dp else 140.dp),
                    ) { Text("했어요", color = c.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                }
                HorizontalDivider(color = c.line)
            }
        }
    }
}
