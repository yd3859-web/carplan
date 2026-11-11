package com.htb.carplan.car

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.model.Stop
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.core.tween

private val Corner = RoundedCornerShape(4.dp)

/**
 * ③ 여행 칸 편집 (주차 중만, 팀장 2026-10-31: 차에서도 폰 수준으로). Left: today's places in route order — tap one to
 * change its time, [위]/[아래] to move it, [빼기] to take it out (one [되살리기] right after). Right: add a place
 * (one search per press) or, with a place chosen, its arrival time. Writes go straight to Firestore, so the phones
 * and the route (10장) follow.
 */
@Composable
fun CarTripEdit(vm: CarHomeViewModel, onDismiss: () -> Unit) {
    val c = LocalCarColors.current
    val trip by vm.trip.collectAsStateWithLifecycle()
    val t = trip
    var selectedId by remember { mutableStateOf<String?>(null) }
    var removed by remember { mutableStateOf<Stop?>(null) }
    // Half screen: one panel at a time — the list, or (after a tap) the time / add panel full width.
    val compact = LocalCompact.current
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.requestLocation() } // search near the car
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        CarEnter(Entry.SCREEN) {
        Column(Modifier.fillMaxSize().background(c.bg).padding(horizontal = 40.dp, vertical = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CarBackButton(onBack = { if (compact && (adding || selectedId != null)) { adding = false; selectedId = null } else onDismiss() })
                Text("칸 편집", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = c.ink, modifier = Modifier.padding(start = 16.dp))
                t?.let { Text("  ${it.title}", fontSize = 26.sp, color = c.soft, maxLines = 1) }
            }
            if (t == null) {
                Text("오늘 여행이 없어요", color = c.soft, fontSize = 26.sp, modifier = Modifier.padding(top = 24.dp))
                return@Column
            }
            val selected = t.stops.firstOrNull { it.id == selectedId && !it.visited }
            Row(Modifier.fillMaxSize().padding(top = 12.dp)) {
                val panel = compact && (selected != null || adding)
                if (!panel) Column(Modifier.weight(1.2f).fillMaxHeight().padding(end = if (compact) 0.dp else 28.dp)) {
                    if (t.stops.isEmpty()) Text(if (compact) "아직 칸이 없어요 · 아래 [장소 넣기]" else "아직 칸이 없어요 · 오른쪽에서 장소를 넣어요", color = c.soft, fontSize = 26.sp, modifier = Modifier.padding(top = 12.dp))
                    val ahead = t.remaining
                    val motion = LocalMotion.current
                    // Keyed rows: [위]/[아래] slide the place to its new spot, a new place fades in, a removed one fades out.
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f)) {
                        itemsIndexed(t.stops, key = { _, s -> s.id }) { i, s ->
                        Column(if (motion) Modifier.animateItem(fadeInSpec = tween(MOTION_MS), placementSpec = tween(MOTION_MS), fadeOutSpec = tween(MOTION_MS)) else Modifier) {
                            val on = s.id == selectedId
                            val idx = ahead.indexOfFirst { it.id == s.id }
                            val edge by carColor(if (on) c.ink else c.bg, "editEdge")
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 96.dp)
                                    .border(2.dp, edge, Corner)
                                    .clickable(enabled = !s.visited) { selectedId = if (on) null else s.id }
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("${i + 1}", color = c.soft, fontSize = 24.sp, modifier = Modifier.width(44.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(s.place.name.ifBlank { "이름 없는 곳" }, color = if (s.visited) c.soft else c.ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(
                                        when { s.skipped -> "건너뜀"; s.visited -> "다녀옴"; else -> s.time?.let { "$it 도착" } ?: "시간 없음" },
                                        color = c.soft, fontSize = 20.sp,
                                    )
                                }
                                if (!s.visited) {
                                    EditButton("위", enabled = idx > 0) { vm.moveStop(s.id, -1) }
                                    EditButton("아래", enabled = idx in 0 until ahead.size - 1) { vm.moveStop(s.id, 1) }
                                    EditButton("빼기") { vm.removeStop(s.id); removed = s; if (on) selectedId = null }
                                }
                            }
                            HorizontalDivider(color = c.line)
                        }
                        }
                    }
                    // The one undo, right where the driver just pressed (폰의 되살리기와 같은 기록).
                    var lastRemoved by remember { mutableStateOf<Stop?>(null) }
                    removed?.let { lastRemoved = it }
                    CarAppear(removed != null) {
                        val r = lastRemoved ?: return@CarAppear
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${r.place.name} 뺐어요", color = c.ink, fontSize = 24.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            EditButton("되살리기") { vm.restoreStop(r.id); removed = null }
                        }
                    }
                    if (compact) Button(
                        onClick = { adding = true }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 88.dp), shape = Corner,
                        colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.onInk),
                    ) { Text("장소 넣기", fontSize = 28.sp, fontWeight = FontWeight.Bold) }
                }
                if (!compact) VerticalDivider(color = c.line)
                if (!compact || panel) Column(Modifier.weight(1f).fillMaxHeight().padding(start = if (compact) 0.dp else 28.dp)) {
                    // Time ⇄ add: a quick fade, not a jump.
                    androidx.compose.animation.Crossfade(selected?.id, Modifier.fillMaxSize(), animationSpec = carSpec(), label = "panel") { id ->
                        Column(Modifier.fillMaxSize()) {
                            val st = t.stops.firstOrNull { it.id == id && !it.visited }
                            if (st != null) TimePanel(st, onTime = { vm.setStopTime(st, it); selectedId = null }, onClose = { selectedId = null })
                            else AddPanel(vm)
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun EditButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalCarColors.current
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = Corner, border = BorderStroke(1.dp, c.line),
        modifier = Modifier.padding(start = 8.dp).heightIn(min = 76.dp).widthIn(min = 96.dp),
    ) { Text(label, color = if (enabled) c.ink else c.railPassed, fontSize = 24.sp, fontWeight = FontWeight.SemiBold) }
}

/** Arrival time of one place: a 시·분 wheel, or none (the rail then shows the expected time only). */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.TimePanel(stop: Stop, onTime: (String?) -> Unit, onClose: () -> Unit) {
    val c = LocalCarColors.current
    var timed by remember(stop.id) { mutableStateOf(stop.time != null) }
    var hm by remember(stop.id) { mutableStateOf(wheelTime(stop.time, 10 to 0)) }
    Text(stop.place.name, color = c.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    Text("몇 시에 도착할까요?", color = c.soft, fontSize = 20.sp, modifier = Modifier.padding(top = 4.dp))
    Column(Modifier.weight(1f).padding(top = 12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("시간 정하기", timed, Modifier.weight(1f)) { timed = true }
            Chip("시간 없음", !timed, Modifier.weight(1f)) { timed = false }
        }
        CarAppear(timed) { CarTimeWheel(hm.first, hm.second, { h, m -> hm = h to m }) }
    }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CarCloseButton(onClick = onClose, label = "취소")
        val time = if (timed) "%02d:%02d".format(hm.first, hm.second) else null
        Button(
            onClick = { onTime(time) }, modifier = Modifier.weight(1f).heightIn(min = 76.dp), shape = Corner,
            colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.onInk),
        ) { Text(time?.let { "$it 도착으로" } ?: "시간 빼기", fontSize = 26.sp, fontWeight = FontWeight.Bold) }
    }
}

/** Add a place: the navigation's saved places, or type → [내비에서 찾기] (현대 내비) / [목록으로] (Kakao), or the name alone. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.AddPanel(vm: CarHomeViewModel) {
    val c = LocalCarColors.current
    var query by remember { mutableStateOf("") }
    var added by remember { mutableStateOf<String?>(null) }
    fun add(p: Place) { vm.addStop(p, null); added = p.name; query = "" }
    Text("장소 넣기", color = c.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold)
    Text(if (LocalCompact.current) "맨 뒤에 들어가요 · 시간과 순서는 목록에서" else "맨 뒤에 들어가요 · 시간과 순서는 왼쪽에서", color = c.soft, fontSize = 20.sp, modifier = Modifier.padding(top = 4.dp))
    Column(Modifier.weight(1f).padding(top = 12.dp).verticalScroll(rememberScrollState())) {
        CarPlacePicker(vm, query, { query = it; added = null }, picked = null, onPick = ::add, hint = "어디로 갈까요?")
        if (query.isNotBlank()) OutlinedButton(
            // Always possible: the name alone (갈 때 내비 검색으로 찾음, 기존 규칙).
            onClick = { add(Place(name = query.trim())) }, shape = Corner, border = BorderStroke(1.dp, c.line),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 76.dp),
        ) { Text("'${query.trim()}' 이름만 넣기", color = c.ink, fontSize = 24.sp, maxLines = 1) }
        added?.let { Text("$it 넣었어요", color = c.ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp)) }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalCarColors.current
    val bg by carColor(if (on) c.ink else c.bg, "chip")
    val fg by carColor(if (on) c.onInk else c.ink, "chipText")
    Text(
        label, color = fg, fontSize = 24.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier.heightIn(min = 76.dp).background(bg, Corner).border(1.dp, if (on) c.ink else c.line, Corner)
            .clickable(onClick = onClick).padding(top = 22.dp),
    )
}
