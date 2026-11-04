package com.htb.carplan.phone.record

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.htb.carplan.phone.trip.TripDayScreen
import com.htb.carplan.phone.trip.TripRef
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.shared.data.CalendarEvent
import com.htb.carplan.shared.data.CalendarRepository
import com.htb.carplan.shared.data.FirestorePaths
import com.htb.carplan.shared.data.RecordRepository
import com.htb.carplan.shared.data.Session
import com.htb.carplan.shared.data.SessionRepository
import com.htb.carplan.shared.model.BuiltInCategory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 14장 기록 탭: days that have (or can have) records — trips and "일상 기록" days, newest first. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordTabViewModel : ViewModel() {
    private val sessions = SessionRepository()
    private val calendar = CalendarRepository()
    private val records = RecordRepository()
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session
    val message = MutableStateFlow<String?>(null)

    val days: StateFlow<List<CalendarEvent>> = _session.filterNotNull()
        .flatMapLatest { s -> calendar.observeGroups(s.uid).map { gs -> gs.map { it.id } } }
        .flatMapLatest { ids ->
            val today = LocalDate.now()
            calendar.observeEvents(ids, today.minusDays(365).toString(), today.toString())
        }
        .map { list ->
            list.filter { !it.event.deleted && (it.event.categoryId == BuiltInCategory.TRIP.id || it.event.id.startsWith("log-")) }
                .sortedByDescending { it.event.startDate }
        }
        .catch { message.value = it.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val groupList: StateFlow<List<com.htb.carplan.shared.model.Group>> = _session.filterNotNull()
        .flatMapLatest { s -> calendar.observeGroups(s.uid) }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The diary as books: one per trip (every day up to yesterday that has records) and one per 일상 day. null while
     * loading. Today's page lives in the big "오늘" card instead.
     */
    val books: StateFlow<List<DiaryBook>?> = kotlinx.coroutines.flow.combine(days, groupList) { d, g -> d to g }
        .mapLatest { (list, gs) ->
            val today = LocalDate.now()
            kotlinx.coroutines.coroutineScope {
                list.map { ce ->
                    async {
                        val start = LocalDate.parse(ce.event.startDate)
                        val end = minOf(LocalDate.parse(ce.event.endDate.ifBlank { ce.event.startDate }), today.minusDays(1))
                        val isLog = ce.event.id.startsWith("log-")
                        val ds = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
                        val pages = ds.mapIndexed { i, day ->
                            val p = DiaryDay(ce.groupId, ce.event.id, day.toString(), ce.event.title, isLog, dayNo = i + 1)
                            p to summary(p)
                        }.filter { it.second.isNotEmpty() }
                        val members = gs.firstOrNull { it.id == ce.groupId }?.takeIf { !it.solo }?.memberIds.orEmpty()
                        if (pages.isEmpty()) null else DiaryBook(ce.groupId, ce.event, pages, members, totalDays = (LocalDate.parse(ce.event.endDate.ifBlank { ce.event.startDate }).toEpochDay() - start.toEpochDay()).toInt() + 1)
                    }
                }.awaitAll().filterNotNull().sortedByDescending { it.pages.last().first.date }
            }.also { bs -> bs.flatMap { it.memberIds }.distinct().forEach(::loadName) }
        }
        .catch { message.value = it.message; emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _names = MutableStateFlow<Map<String, String>>(emptyMap())
    val names: StateFlow<Map<String, String>> = _names
    private val groupsRepo = com.htb.carplan.shared.data.GroupRepository()
    private fun loadName(uid: String) {
        if (_names.value.containsKey(uid)) return
        _names.value = _names.value + (uid to "")
        viewModelScope.launch { runCatching { groupsRepo.user(uid) }.onSuccess { u -> _names.value = _names.value + (uid to u?.name.orEmpty()) } }
    }
    fun me(): String? = _session.value?.uid
    fun nameOf(uid: String?): String = when {
        uid == null -> "일행"
        uid == me() -> "나"
        else -> _names.value[uid]?.takeIf { it.isNotBlank() } ?: "일행"
    }

    private val trips = com.htb.carplan.shared.data.TripRepository()
    /** One read of the day's comments (the diary page shows them under their place). */
    suspend fun comments(p: DiaryDay): List<com.htb.carplan.shared.model.Comment> =
        runCatching { kotlinx.coroutines.withTimeout(8_000) { trips.observeDayComments(p.groupId, p.eventId, p.date).first() } }
            .getOrDefault(emptyList()).also { cs -> cs.map { it.uid }.distinct().forEach(::loadName) }

    init { viewModelScope.launch { runCatching { sessions.ensureSession() }.onSuccess { _session.value = it } } }

    suspend fun summary(p: DiaryDay) = runCatching { records.daySummary(p.groupId, p.eventId, p.date) }.getOrDefault(emptyList())

    /** "오늘 기록 칸 직접 추가": opens (creating if needed) today's 일상 기록 and hands back its ref. */
    fun openTodayLog(onReady: (TripRef) -> Unit) {
        val s = _session.value ?: return
        viewModelScope.launch {
            val today = LocalDate.now().toString()
            runCatching { records.ensureDailyLog(s.soloGroupId, today, s.uid) }
                .onSuccess { onReady(TripRef(s.soloGroupId, it, today)) }
                .onFailure { message.value = it.message }
        }
    }
}

/** One page of the diary: a trip day or an 일상 기록 day. */
data class DiaryDay(val groupId: String, val eventId: String, val date: String, val title: String, val isLog: Boolean, val dayNo: Int = 1)

/** One trip (or one 일상 day) in the diary: the days that have records, with their records. */
data class DiaryBook(
    val groupId: String,
    val event: com.htb.carplan.shared.model.Event,
    val pages: List<Pair<DiaryDay, List<com.htb.carplan.shared.model.Record>>>,
    val memberIds: List<String>,
    val totalDays: Int,
) {
    val isLog get() = event.id.startsWith("log-")
    val photos get() = pages.flatMap { (_, rs) -> rs.flatMap { it.photoUrls } }
    val places get() = pages.flatMap { (_, rs) -> rs.map { it.place.name.ifBlank { "이름 없는 곳" } } }.distinct()
}

@Composable
fun RecordTab(vm: RecordTabViewModel = viewModel()) {
    val days by vm.days.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val names by vm.names.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf<List<String>?>(null) }
    // The diary page being read: (book key, date).
    var reading by remember { mutableStateOf<Pair<DiaryBook, DiaryDay>?>(null) }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    com.htb.carplan.phone.ui.OnTabTapAgain { if (open != null) open = null else if (reading != null) reading = null else list.animateScrollToItem(0) }
    val today = LocalDate.now()
    val todayPages = remember(days) {
        days.filter { ce -> ce.event.startDate <= today.toString() && ce.event.endDate.ifBlank { ce.event.startDate } >= today.toString() }
            .map { ce -> DiaryDay(ce.groupId, ce.event.id, today.toString(), ce.event.title, ce.event.id.startsWith("log-"),
                dayNo = (today.toEpochDay() - LocalDate.parse(ce.event.startDate).toEpochDay()).toInt() + 1) }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("기록", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.padding(top = 16.dp))
            Text("다녀온 날을 한 장씩 모아 둬요", fontSize = 15.sp, color = Palette.TextSoft)
            message?.let { Text(it, color = Palette.Sunday, fontSize = 15.sp) }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp), state = list) {
            item { TodayPage(today, todayPages, vm) { ref -> open = ref } }
            val bs = books
            if (bs == null) item { Text("기록을 펼치는 중…", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 28.dp)) }
            else if (bs.isEmpty()) item {
                Text("아직 모아 둔 날이 없어요.\n여행에서 차를 세우면 그곳이 여기 한 장으로 남아요.", fontSize = 16.sp, color = Palette.TextSoft, lineHeight = 24.sp, modifier = Modifier.padding(top = 28.dp))
            }
            else bs.groupBy { java.time.YearMonth.from(LocalDate.parse(it.pages.last().first.date)) }.forEach { (ym, inMonth) ->
                item(key = "m$ym") { MonthHeader(ym, inMonth) }
                items(inMonth, key = { it.groupId + it.event.id }) { b ->
                    BookCard(b, names.let { _ -> b.memberIds.filter { it != vm.me() }.map(vm::nameOf) }) { page -> reading = b to page }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    reading?.let { (b, p) ->
        com.htb.carplan.phone.ui.BackScreen(onBack = { reading = null }) { back ->
            DiaryPage(b, p, vm, onBack = back, onPage = { reading = b to it }, onEdit = { open = listOf(p.groupId, p.eventId, p.date) })
        }
    }
    open?.let { (g, e, d) -> com.htb.carplan.phone.ui.BackScreen(onBack = { open = null }) { back -> TripDayScreen(TripRef(g, e, d), onBack = back) } }
}

@Composable
private fun MonthHeader(ym: java.time.YearMonth, books: List<DiaryBook>) {
    val trips = books.count { !it.isLog }
    val label = if (ym.year == LocalDate.now().year) "${ym.monthValue}월" else "${ym.year}년 ${ym.monthValue}월"
    Row(Modifier.fillMaxWidth().padding(top = 30.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(label, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
        Text(buildList { if (trips > 0) add("여행 ${trips}번"); val logs = books.size - trips; if (logs > 0) add("일상 ${logs}날") }.joinToString(" · "),
            fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(start = 10.dp, bottom = 3.dp))
    }
}

/**
 * One trip as a book cover: the trip's first photo big, title, dates, who came, the places. One page = the whole
 * cover opens it; several days = a line per day.
 */
@Composable
private fun BookCard(b: DiaryBook, with: List<String>, onOpen: (DiaryDay) -> Unit) {
    val first = b.pages.first().first
    val single = b.pages.size == 1
    val photos = b.photos
    Column(Modifier.fillMaxWidth().clickable { onOpen(if (single) first else b.pages.first().first) }.padding(vertical = 14.dp)) {
        photos.firstOrNull()?.let { url ->
            coil3.compose.AsyncImage(
                model = url, contentDescription = "${b.event.title} 대표 사진", contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(Corner).padding(bottom = 0.dp),
            )
            Spacer(Modifier.height(12.dp))
        }
        Text(if (b.isLog) "일상" else b.event.title.ifBlank { "여행" }, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
        Text(dateRange(b), fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 2.dp))
        val bits = buildList {
            add("${b.places.size}곳")
            if (photos.isNotEmpty()) add("사진 ${photos.size}장")
            if (with.isNotEmpty()) add("함께 · " + with.distinct().joinToString("·"))
        }
        Text(bits.joinToString("  ·  "), fontSize = 15.sp, color = Palette.ChipText, modifier = Modifier.padding(top = 2.dp))
        b.pages.flatMap { it.second }.firstOrNull { it.note.isNotBlank() }?.let {
            Text("“${it.note}”", fontSize = 17.sp, color = Palette.TextStrong, modifier = Modifier.padding(top = 8.dp))
        }
        if (single) {
            Text(b.places.joinToString(" → "), fontSize = 15.sp, color = Palette.TextSoft, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        } else {
            Column(Modifier.padding(top = 8.dp)) {
                b.pages.forEach { (p, rs) ->
                    val d = LocalDate.parse(p.date)
                    Row(Modifier.fillMaxWidth().clip(Corner).clickable { onOpen(p) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${p.dayNo}일차", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.width(52.dp))
                        Text("${d.monthValue}.${d.dayOfMonth} ${d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.KOREAN)}", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.width(64.dp))
                        Text(rs.map { it.place.name.ifBlank { "이름 없는 곳" } }.distinct().joinToString(" → "), fontSize = 15.sp, color = Palette.ChipText, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("›", fontSize = 20.sp, color = Palette.TextSoft, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
    HorizontalDivider(color = Palette.Line)
}

private fun dateRange(b: DiaryBook): String {
    val s = LocalDate.parse(b.event.startDate)
    val e = LocalDate.parse(b.event.endDate.ifBlank { b.event.startDate })
    fun dow(d: LocalDate) = d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.KOREAN)
    return when {
        s == e -> "${s.monthValue}월 ${s.dayOfMonth}일 ${dow(s)}요일"
        s.month == e.month -> "${s.monthValue}월 ${s.dayOfMonth}일(${dow(s)}) – ${e.dayOfMonth}일(${dow(e)}) · ${b.totalDays - 1}박 ${b.totalDays}일"
        else -> "${s.monthValue}월 ${s.dayOfMonth}일 – ${e.monthValue}월 ${e.dayOfMonth}일 · ${b.totalDays - 1}박 ${b.totalDays}일"
    }
}

@Composable
private fun TodayPage(today: LocalDate, pages: List<DiaryDay>, vm: RecordTabViewModel, onOpen: (List<String>) -> Unit) {
    // Several things today (two trips, 일상): the page that already has records leads, else the first trip.
    val loaded by produceState<List<Pair<DiaryDay, List<com.htb.carplan.shared.model.Record>>>>(emptyList(), pages) { value = pages.map { it to vm.summary(it) } }
    val chosen = loaded.firstOrNull { it.second.isNotEmpty() } ?: loaded.firstOrNull { !it.first.isLog } ?: loaded.firstOrNull()
    val page = chosen?.first ?: pages.firstOrNull { !it.isLog } ?: pages.firstOrNull()
    val recs = chosen?.second.orEmpty()
    Column(Modifier.fillMaxWidth().padding(top = 14.dp).border(1.dp, Palette.SheetEdge, Corner).padding(18.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${today.dayOfMonth}", fontSize = 40.sp, fontWeight = FontWeight.Black, color = Palette.TextStrong)
            Text("  ${today.monthValue}월 · ${today.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.KOREAN)} · 오늘", fontSize = 17.sp, color = Palette.TextSoft, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (recs.isEmpty()) Text("아직 쓴 게 없어요. 차를 세우면 칸이 생기고, 직접 쓸 수도 있어요.", fontSize = 16.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 4.dp))
        else DiaryBody(page!!, recs)
        Button(
            onClick = { if (page != null && !page.isLog) onOpen(listOf(page.groupId, page.eventId, page.date)) else vm.openTodayLog { onOpen(listOf(it.groupId, it.eventId, it.date)) } },
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(52.dp), shape = Corner,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy),
        ) { Text(if (recs.isEmpty()) "오늘 쓰기" else "오늘 이어 쓰기", fontWeight = FontWeight.SemiBold, fontSize = 17.sp) }
    }
}

/** Title, the places in order, the first line someone wrote, and up to 4 photos. */
@Composable
private fun DiaryBody(p: DiaryDay, recs: List<com.htb.carplan.shared.model.Record>) {
    Text(if (p.isLog) "일상" else p.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
    // Each place once, in the order first reached (a day driven twice, or a test day, must not repeat the list).
    val path = recs.map { it.place.name.ifBlank { "이름 없는 곳" } }.distinct()
    if (recs.isNotEmpty()) Text(path.joinToString(" → ") + "  ·  ${path.size}곳", fontSize = 15.sp, color = Palette.ChipText, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    else Text("들른 곳 없음", fontSize = 15.sp, color = Palette.TextSoft)
    recs.firstOrNull { it.note.isNotBlank() }?.let { Text("“${it.note}”", fontSize = 17.sp, color = Palette.TextStrong, modifier = Modifier.padding(top = 6.dp)) }
    val all = recs.flatMap { it.photoUrls }
    // A photo opens big (all of the day's photos, sideways); the rest of the card opens the day.
    var viewing by remember { mutableStateOf<Int?>(null) }
    viewing?.let { com.htb.carplan.phone.ui.PhotoViewer(all, it) { viewing = null } }
    if (all.isNotEmpty()) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        all.take(4).forEachIndexed { i, url -> coil3.compose.AsyncImage(model = url, contentDescription = "사진 크게 보기", contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.size(72.dp).clip(Corner).clickable { viewing = i }) }
        if (all.size > 4) Text("+${all.size - 4}", fontSize = 15.sp, color = Palette.TextSoft)
    }
}
