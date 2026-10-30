package com.htb.carplan.phone.trip

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.shared.calendar.RouteMath.hasCoords
import com.htb.carplan.shared.model.Stop
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * 3장 그날 장소 칸: date tabs across the trip, the day's stops in route order
 * (long-press to drag), the drive between them, 추가 / 빼기 / 되살리기 / [동선 최소로 정렬].
 * Everything saves immediately; other members and the car see it live.
 */
@Composable
fun TripDayScreen(ref: TripRef, onBack: () -> Unit) {
    val vm: TripDayViewModel = viewModel(key = ref.groupId + ref.eventId, factory = tripVmFactory(ref))
    val event by vm.event.collectAsStateWithLifecycle()
    val dates by vm.dates.collectAsStateWithLifecycle()
    val date by vm.date.collectAsStateWithLifecycle()
    val stops by vm.stops.collectAsStateWithLifecycle()
    val removed by vm.removed.collectAsStateWithLifecycle()
    val legs by vm.legs.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val me by vm.uid.collectAsStateWithLifecycle()
    vm.names.collectAsStateWithLifecycle().value // recompose when a name arrives

    var editing by remember { mutableStateOf<Stop?>(null) }
    var adding by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().background(Palette.Background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = Palette.TextStrong) }
            Column(Modifier.weight(1f)) {
                Text(event?.title?.ifBlank { null } ?: "여행", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
                // 4장에서 그룹 고르기가 생기면 여기 그룹 이름이 들어간다.
                Text("나만 그룹 · 모두가 편집 · 바로 저장", fontSize = 12.sp, color = Palette.TextSoft)
            }
            if (busy) Text("저장 중", fontSize = 12.sp, color = Palette.TextSoft, modifier = Modifier.padding(end = 12.dp))
        }

        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dates.forEachIndexed { i, d -> DateTab(d, i + 1, selected = d == date) { vm.showDate(d) } }
        }

        error?.let {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("오류: $it", color = Palette.Sunday, fontSize = 12.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = vm::clearError) { Icon(Icons.Filled.Close, "닫기", tint = Palette.TextSoft) }
            }
        }

        val listState = rememberLazyListState()
        val reorder = rememberReorderableLazyListState(listState) { from, to ->
            vm.moveLocally(from.key as String, to.key as String)
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            if (stops.isEmpty()) item { Text("아직 장소 칸이 없어요. 아래에서 추가해요", color = Palette.TextSoft, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp)) }
            items(stops, key = { it.id }) { stop ->
                ReorderableItem(reorder, key = stop.id) { dragging ->
                    val i = stops.indexOfFirst { it.id == stop.id }
                    Column {
                        StopRow(
                            index = i,
                            stop = stop,
                            editedBy = recentEditor(stop, me, vm::nameOf),
                            dragging = dragging,
                            modifier = Modifier.longPressDraggableHandle(onDragStopped = { vm.commitOrder() }),
                            onClick = { editing = stop },
                            onRemove = { vm.remove(stop.id) },
                        )
                        if (i < stops.lastIndex) LegRow(legs[stop.id])
                    }
                }
            }
            if (removed.isNotEmpty()) {
                item { Text("뺀 칸", fontSize = 12.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) }
                items(removed, key = { "removed-" + it.id }) { stop ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${vm.whoDid(stop.updatedBy)} ${stop.place.name}를(을) 뺐어요",
                            fontSize = 13.sp, color = Palette.TextSoft, textDecoration = TextDecoration.LineThrough, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.restore(stop.id) }) { Text("되살리기", color = Palette.Teal) }
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }

        // Bottom padding keeps the buttons above the floating tab bar (AppRoot draws it over everything).
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = vm::sortShortest,
                enabled = stops.count { it.place.hasCoords() } >= 3,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) { Text("동선 최소로 정렬" + if (vm.kakaoAvailable) "" else " (직선거리 기준)", color = Palette.Navy) }
            Button(
                onClick = { adding = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy),
            ) { Text("+ 장소 칸 추가", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
    }

    if (adding) {
        StopEditSheet(vm, existing = null, onSave = { p, t, m -> vm.addStop(p, t, m); adding = false }, onDismiss = { adding = false })
    }
    editing?.let { stop ->
        StopEditSheet(vm, existing = stop, onSave = { p, t, m -> vm.updateStop(stop.id, p, t, m); editing = null }, onDismiss = { editing = null })
    }
}

/** "○○님이 방금 수정" (Q07): someone else touched this stop in the last 5 minutes. */
private fun recentEditor(stop: Stop, me: String?, nameOf: (String?) -> String): String? {
    val by = stop.updatedBy ?: return null
    if (by == me) return null
    val at = stop.updatedAt?.toDate()?.time ?: return null
    if (System.currentTimeMillis() - at > 5 * 60_000) return null
    return "${nameOf(by)}님이 방금 수정"
}

@Composable
private fun DateTab(date: String, dayNo: Int, selected: Boolean, onClick: () -> Unit) {
    val d = LocalDate.parse(date)
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = if (selected) Palette.Navy else Palette.InputBg) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${dayNo}일차", fontSize = 11.sp, color = if (selected) Color.White.copy(alpha = 0.7f) else Palette.TextSoft)
            Text("${d.monthValue}/${d.dayOfMonth} ${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (selected) Color.White else Palette.TextStrong)
        }
    }
}

@Composable
private fun StopRow(index: Int, stop: Stop, editedBy: String?, dragging: Boolean, modifier: Modifier, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .shadow(if (dragging) 8.dp else 0.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(if (dragging) Color.White else Palette.Panel)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(if (stop.place.hasCoords()) Palette.Teal else Palette.TextSoft), contentAlignment = Alignment.Center) {
            Text("${index + 1}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                stop.time?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Navy, modifier = Modifier.padding(end = 8.dp)) }
                Text(stop.place.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
            }
            val sub = listOfNotNull(
                stop.place.address.takeIf { it.isNotBlank() },
                if (!stop.place.hasCoords()) "좌표 없음" else null,
                editedBy,
            ).joinToString("  ·  ")
            if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = Palette.TextSoft)
            if (stop.memo.isNotBlank()) Text(stop.memo, fontSize = 13.sp, color = Palette.ChipText, modifier = Modifier.padding(top = 2.dp))
        }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "빼기", tint = Palette.TextSoft) }
    }
}

/** The drive between two stops; Kakao time when there is a key, a straight-line guess otherwise. */
@Composable
private fun LegRow(leg: LegInfo?) {
    Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(12.dp))
        Box(Modifier.width(26.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(Palette.Teal.copy(alpha = 0.4f)))
        }
        val label = when {
            leg == null -> "🚗 이동"
            leg.exact -> "🚗 ${minutes(leg.seconds)} · ${km(leg.meters)}"
            else -> "🚗 약 ${minutes(leg.seconds)} · ${km(leg.meters)} (직선)"
        }
        Text(label, Modifier.padding(start = 12.dp), fontSize = 12.sp, color = Palette.Teal)
    }
}

private fun minutes(seconds: Int) = if (seconds >= 3600) "${seconds / 3600}시간 ${seconds % 3600 / 60}분" else "${maxOf(1, seconds / 60)}분"
private fun km(meters: Int) = if (meters >= 1000) "%.1fkm".format(meters / 1000.0) else "${meters}m"

private fun tripVmFactory(ref: TripRef) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = TripDayViewModel(ref.groupId, ref.eventId, ref.date) as T
}
