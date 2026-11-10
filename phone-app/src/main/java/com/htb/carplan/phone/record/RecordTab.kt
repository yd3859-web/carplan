package com.htb.carplan.phone.record

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import kotlinx.coroutines.tasks.await
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

    // ---------- D 다음 여행 추천 (NextTrip.kt) ----------
    val suggest = MutableStateFlow<NextTripState>(NextTripState.Idle)
    private val avoided = mutableListOf<String>() // [다른 곳]을 누른 동네는 다음 추천에서 뺀다

    fun loadSuggestion(context: android.content.Context, force: Boolean = false) {
        val bs = books.value?.filterNot { it.isLog } ?: return
        // 기록은 그룹마다 따로 흘러들어와서 books가 몇 번 바뀐다: 추천이 이미 떠 있거나 고르는 중일 때만 멈추고,
        // "기록이 모자라요" 상태였다면 새로 들어온 기록으로 다시 판단한다 (2026-11-09 에뮬: 7곳인데 모자라다고 나온 버그).
        if (!force && (suggest.value is NextTripState.Ready || suggest.value is NextTripState.Loading)) return
        if (bs.sumOf { b -> b.pages.sumOf { it.second.size } } < 3) {
            suggest.value = NextTripState.Off("기록이 세 곳쯤 쌓이면 다음 여행을 추천해 드려요"); return
        }
        viewModelScope.launch {
            // 저장해 둔 지난 추천 먼저 (시연장 인터넷 대비), 그 뒤 새로 받아 바꾼다.
            if (!force && suggest.value is NextTripState.Idle) NextTripStore.load(context)?.let { suggest.value = NextTripState.Ready(it) }
            if (suggest.value !is NextTripState.Ready) suggest.value = NextTripState.Loading
            val t = NextTripLoader.load(context, bs, kakaoApi, avoided)
            if (t != null) { suggest.value = NextTripState.Ready(t); NextTripStore.save(context, t) }
            else if (suggest.value is NextTripState.Loading) suggest.value = NextTripState.Off("추천할 거리를 찾지 못했어요 · 나중에 다시 볼게요")
        }
    }

    fun otherSuggestion(context: android.content.Context) {
        (suggest.value as? NextTripState.Ready)?.trip?.region?.let { if (it !in avoided) avoided += it }
        suggest.value = NextTripState.Loading
        loadSuggestion(context, force = true)
    }

    /** [이 여행 만들기]: 나만 그룹에 여행 하나 + 추천 장소가 칸으로, 바로 그 레일을 연다. */
    fun makeTrip(t: NextTrip, onOpen: (TripRef) -> Unit) {
        val s = _session.value ?: return
        viewModelScope.launch {
            runCatching {
                val eid = calendar.addEvent(s.soloGroupId, com.htb.carplan.shared.model.Event(
                    categoryId = com.htb.carplan.shared.model.BuiltInCategory.TRIP.id,
                    title = t.title, startDate = t.date, endDate = t.date, allDay = true,
                    memo = t.reason, createdBy = s.uid))
                t.stops.forEach { p -> trips.addStop(s.soloGroupId, eid, t.date, p, null, "", s.uid) }
                onOpen(TripRef(s.soloGroupId, eid, t.date))
            }.onFailure { message.value = it.message }
        }
    }

    /** One read of the day's comments (the diary page shows them under their place). */
    suspend fun comments(p: DiaryDay): List<com.htb.carplan.shared.model.Comment> =
        runCatching { kotlinx.coroutines.withTimeout(8_000) { trips.observeDayComments(p.groupId, p.eventId, p.date).first() } }
            .getOrDefault(emptyList()).also { cs -> cs.map { it.uid }.distinct().forEach(::loadName) }

    /**
     * 시험용 (debug, "기록" 제목 길게): every record that has a line but no photo gets one or two of the sample photos,
     * so the diary can be seen with pictures. Says how many went in, or the first error.
     */
    fun fillSamplePhotos(context: android.content.Context) {
        val bs = books.value ?: return
        viewModelScope.launch {
            val photos = runCatching { context.assets.list("demo").orEmpty().sorted().map { n -> context.assets.open("demo/$n").use { it.readBytes() } } }.getOrDefault(emptyList())
            if (photos.isEmpty()) { message.value = "사진 파일이 없는 빌드예요"; return@launch }
            var turn = 0; var added = 0; var error: String? = null; var storageRefused = false
            message.value = "사진 넣는 중…"
            for (b in bs) for ((p, recs) in b.pages) for (r in recs.filter { it.note.isNotBlank() && (it.photoUrls.isEmpty() || it.photoUrls.all { u -> "picsum.photos" in u }) }) {
                // Web samples from an earlier run (Storage was refused then): swap them for real uploads when Storage works.
                val samples = r.photoUrls.filter { "picsum.photos" in it }
                if (samples.isNotEmpty() && storageRefused) continue
                repeat(if (r.note.length > 14) 2 else 1) {
                    val n = turn++
                    runCatching {
                        if (storageRefused) error("storage") else records.addPhoto(p.groupId, p.eventId, p.date, r.id, photos[n % photos.size])
                    }.recoverCatching {
                        // Storage refused (rules can't read Firestore yet): a sample picture from the web, test data only.
                        storageRefused = true
                        com.google.firebase.firestore.FirebaseFirestore.getInstance()
                            .document("${FirestorePaths.records(p.groupId, p.eventId, p.date)}/${r.id}")
                            .update("photoUrls", com.google.firebase.firestore.FieldValue.arrayUnion("https://picsum.photos/seed/carplan${r.id.take(6)}$n/1200/900"))
                            .await()
                    }.onSuccess { added++ }.onFailure { if (error == null) error = it.message }
                }
                if (samples.isNotEmpty() && !storageRefused) runCatching {
                    com.google.firebase.firestore.FirebaseFirestore.getInstance()
                        .document("${FirestorePaths.records(p.groupId, p.eventId, p.date)}/${r.id}")
                        .update("photoUrls", com.google.firebase.firestore.FieldValue.arrayRemove(*samples.toTypedArray())).await()
                }
            }
            message.value = if (added > 0) "사진 ${added}장 넣었어요" + (if (storageRefused) " (Storage가 막혀 웹 샘플 사진)" else "") + " · 탭을 다시 열면 보여요" else "못 넣었어요 · ${error ?: "넣을 칸이 없어요"}"
        }
    }

    private val kakaoApi by lazy { com.htb.carplan.shared.net.KakaoApi(com.htb.carplan.phone.BuildConfig.KAKAO_REST_KEY) }
    private val roadCache = mutableMapOf<String, List<List<Pair<Double, Double>>>>()

    /**
     * The road between each two places of a day, for the moving car in the diary (Kakao Mobility, once per day read,
     * kept while the tab lives — 외부 API 한도). No key, a failure or a very long day: a straight line for that leg.
     */
    suspend fun roads(p: DiaryDay, pts: List<Pair<Double, Double>>): List<List<Pair<Double, Double>>> {
        val key = p.groupId + p.eventId + p.date + pts.hashCode()
        roadCache[key]?.let { return it }
        // A1: the roads the car really drove first; a Kakao route only for legs it did not record.
        val drives = drivesOf(p)
        val legs = pts.zipWithNext().mapIndexed { i, (a, b) ->
            if (a == b) listOf(a, b)
            else if (drives.isNotEmpty()) com.htb.carplan.shared.calendar.TrackMath.legBetween(drives, a, b) ?: kakaoLeg(i, a, b)
            else kakaoLeg(i, a, b)
        }
        roadCache[key] = legs
        return legs
    }

    private val drivesCache = mutableMapOf<String, List<List<Pair<Double, Double>>>>()
    private suspend fun drivesOf(p: DiaryDay) = drivesCache.getOrPut(p.groupId + p.eventId + p.date) { records.tracks(p.groupId, p.eventId, p.date) }

    /**
     * A4 우리 데이터 비율 (Claude 제안 2026-11-10): of a month's diary roads (legs between the places of each day), how
     * many the car's own drives draw — each one a Kakao route search not made. One tracks read per day, kept.
     */
    suspend fun ourShare(inMonth: List<DiaryBook>): Pair<Int, Int> {
        var covered = 0; var all = 0
        inMonth.flatMap { it.pages }.forEach { (p, rs) ->
            val pts = rs.map { it.place }.filter { it.lat != 0.0 || it.lng != 0.0 }.map { it.lat to it.lng }
            val (c, n) = com.htb.carplan.shared.calendar.TrackMath.coveredLegs(drivesOf(p), pts)
            covered += c; all += n
        }
        return covered to all
    }

    private suspend fun kakaoLeg(i: Int, a: Pair<Double, Double>, b: Pair<Double, Double>): List<Pair<Double, Double>> =
            if (a == b) listOf(a, b)
            else if (!kakaoApi.available || i >= 12) listOf(a, b)
            else runCatching {
                kakaoApi.route(com.htb.carplan.shared.model.Place(lat = a.first, lng = a.second), com.htb.carplan.shared.model.Place(lat = b.first, lng = b.second)).path
            }.getOrNull()?.takeIf { it.size >= 2 }?.let { listOf(a) + it + listOf(b) } ?: listOf(a, b)

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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(books) { if (books != null) vm.loadSuggestion(ctx) }
    val todayPages = remember(days) {
        days.filter { ce -> ce.event.startDate <= today.toString() && ce.event.endDate.ifBlank { ce.event.startDate } >= today.toString() }
            .map { ce -> DiaryDay(ce.groupId, ce.event.id, today.toString(), ce.event.title, ce.event.id.startsWith("log-"),
                dayNo = (today.toEpochDay() - LocalDate.parse(ce.event.startDate).toEpochDay()).toInt() + 1) }
    }
    // A reading column: on a wide screen the diary stays book-width in the middle.
    Column(Modifier.fillMaxSize().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 20.dp)) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Text("기록", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong,
                modifier = Modifier.padding(top = 16.dp).then(
                    if (com.htb.carplan.phone.BuildConfig.DEBUG) Modifier.combinedClickable(onClick = {}, onLongClick = { vm.fillSamplePhotos(ctx) }) else Modifier))
            Text("다녀온 날을 한 장씩 모아 둬요", fontSize = 15.sp, color = Palette.TextSoft)
            message?.let { Text(it, color = Palette.Sunday, fontSize = 15.sp) }
        }
        LazyColumn(Modifier.weight(1f).widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 20.dp), state = list) {
            item { TodayPage(today, todayPages, vm) { ref -> open = ref } }
            val bs = books
            if (bs == null) item { Text("기록을 펼치는 중…", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 28.dp)) }
            else if (bs.isEmpty()) item {
                Text("아직 모아 둔 날이 없어요.\n여행에서 차를 세우면 그곳이 여기 한 장으로 남아요.", fontSize = 16.sp, color = Palette.TextSoft, lineHeight = 24.sp, modifier = Modifier.padding(top = 28.dp))
            }
            else bs.groupBy { java.time.YearMonth.from(LocalDate.parse(it.pages.last().first.date)) }.forEach { (ym, inMonth) ->
                item(key = "m$ym") { MonthHeader(ym, inMonth, vm) }
                items(inMonth, key = { it.groupId + it.event.id }) { b ->
                    BookCard(b, names.let { _ -> b.memberIds.filter { it != vm.me() }.map(vm::nameOf) }) { page -> reading = b to page }
                }
            }
            if (bs != null) item(key = "next-trip") {
                val st by vm.suggest.collectAsStateWithLifecycle()
                NextTripCard(st,
                    onMake = { t -> vm.makeTrip(t) { ref -> open = listOf(ref.groupId, ref.eventId, ref.date) } },
                    onOther = { vm.otherSuggestion(ctx) })
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
private fun MonthHeader(ym: java.time.YearMonth, books: List<DiaryBook>, vm: RecordTabViewModel) {
    val trips = books.count { !it.isLog }
    val label = if (ym.year == LocalDate.now().year) "${ym.monthValue}월" else "${ym.year}년 ${ym.monthValue}월"
    Row(Modifier.fillMaxWidth().padding(top = 30.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(label, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
        Text(buildList { if (trips > 0) add("여행 ${trips}번"); val logs = books.size - trips; if (logs > 0) add("일상 ${logs}날") }.joinToString(" · "),
            fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(start = 10.dp, bottom = 3.dp))
    }
    // A4: how much of this month's map our own car drew (no line until a drive was recorded).
    val share by androidx.compose.runtime.produceState<Pair<Int, Int>?>(null, books) { value = runCatching { vm.ourShare(books) }.getOrNull() }
    share?.takeIf { it.first > 0 }?.let { (c, n) ->
        Text("길 ${n}구간 중 ${c}구간(${c * 100 / n}%)은 우리 차가 달린 길 · 길찾기 ${c}번 아낌", fontSize = 14.sp, color = Palette.TextSoft)
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
        // No photo yet: the day's way on a real map (정원 2026-11-09 "흰 바탕 선 말고 지도로"), so the book still has a face.
        if (photos.isEmpty()) {
            val pts = b.pages.flatMap { it.second }.map { it.place }.filter { it.lat != 0.0 || it.lng != 0.0 }.map { it.lat to it.lng }
            if (pts.size >= 2) {
                com.htb.carplan.phone.ui.SimpleMap(
                    centerLat = pts.first().first, centerLng = pts.first().second, zoom = 12,
                    markers = pts.mapIndexed { i, (la, lo) -> com.htb.carplan.phone.ui.MapMarker(la, lo, "${i + 1}", com.htb.carplan.phone.ui.MapMarker.Kind.STOP) },
                    path = pts, fit = pts, height = 150.dp,
                )
                Spacer(Modifier.height(10.dp))
            }
        }
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
    // No box around it (sections aren't boxed): the big date and a line under it do the job (디자인 평가 2026-11-04).
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
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
        HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(top = 20.dp))
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

