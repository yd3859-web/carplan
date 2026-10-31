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

private val Corner = RoundedCornerShape(4.dp)

/**
 * ③ 여행 칸 편집 (주차 중만, 정원 2026-10-31: 차에서도 폰 수준으로). Left: today's places in route order — tap one to
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
    LaunchedEffect(Unit) { vm.requestLocation() } // search near the car
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(Modifier.fillMaxSize().background(c.bg).padding(horizontal = 40.dp, vertical = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CarBackButton(onBack = onDismiss)
                Text("칸 편집", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = c.ink, modifier = Modifier.padding(start = 16.dp))
                t?.let { Text("  ${it.title}", fontSize = 26.sp, color = c.soft, maxLines = 1) }
            }
            if (t == null) {
                Text("오늘 여행이 없어요", color = c.soft, fontSize = 26.sp, modifier = Modifier.padding(top = 24.dp))
                return@Column
            }
            val selected = t.stops.firstOrNull { it.id == selectedId && !it.visited }
            Row(Modifier.fillMaxSize().padding(top = 12.dp)) {
                Column(Modifier.weight(1.2f).fillMaxHeight().padding(end = 28.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        if (t.stops.isEmpty()) Text("아직 칸이 없어요 · 오른쪽에서 장소를 넣어요", color = c.soft, fontSize = 26.sp, modifier = Modifier.padding(top = 12.dp))
                        val ahead = t.remaining
                        t.stops.forEachIndexed { i, s ->
                            val on = s.id == selectedId
                            val idx = ahead.indexOfFirst { it.id == s.id }
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 96.dp)
                                    .then(if (on) Modifier.border(2.dp, c.ink, Corner) else Modifier)
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
                    // The one undo, right where the driver just pressed (폰의 되살리기와 같은 기록).
                    removed?.let { r ->
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${r.place.name} 뺐어요", color = c.ink, fontSize = 24.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            EditButton("되살리기") { vm.restoreStop(r.id); removed = null }
                        }
                    }
                }
                VerticalDivider(color = c.line)
                Column(Modifier.weight(1f).fillMaxHeight().padding(start = 28.dp)) {
                    if (selected != null) TimePanel(selected, onTime = { vm.setStopTime(selected, it); selectedId = null }, onClose = { selectedId = null })
                    else AddPanel(vm)
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

/** Arrival time of one place: hour chips + 30분, or none (the rail then shows the expected time only). */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.TimePanel(stop: Stop, onTime: (String?) -> Unit, onClose: () -> Unit) {
    val c = LocalCarColors.current
    val cur = stop.time?.split(":")
    var hour by remember(stop.id) { mutableStateOf(cur?.getOrNull(0)?.toIntOrNull()) }
    var half by remember(stop.id) { mutableStateOf(cur?.getOrNull(1) == "30") }
    Text(stop.place.name, color = c.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    Text("몇 시에 도착할까요?", color = c.soft, fontSize = 20.sp, modifier = Modifier.padding(top = 4.dp))
    Column(Modifier.weight(1f).padding(top = 12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        (6..22).chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { h -> Chip("${h}시", hour == h, Modifier.weight(1f)) { hour = h } }
                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("정각", !half, Modifier.weight(1f)) { half = false }
            Chip("30분", half, Modifier.weight(1f)) { half = true }
            Chip("시간 없음", hour == null, Modifier.weight(1f)) { hour = null }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CarCloseButton(onClick = onClose, label = "취소")
        val time = hour?.let { "%02d:%02d".format(it, if (half) 30 else 0) }
        Button(
            onClick = { onTime(time) }, modifier = Modifier.weight(1f).heightIn(min = 76.dp), shape = Corner,
            colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.onInk),
        ) { Text(time?.let { "$it 도착으로" } ?: "시간 빼기", fontSize = 26.sp, fontWeight = FontWeight.Bold) }
    }
}

/** Add a place: type, press [찾기] once, pick. No key / nothing found → add by name (the navigation searches it later). */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.AddPanel(vm: CarHomeViewModel) {
    val c = LocalCarColors.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf<String?>(null) }
    // The car keyboard covers half the screen: it goes away as soon as there is something to pick.
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    fun search() {
        if (query.isBlank() || busy) return
        keyboard?.hide(); focus.clearFocus()
        busy = true
        scope.launch { results = vm.searchPlaces(query); busy = false }
    }
    fun add(p: Place) { vm.addStop(p, null); added = p.name; query = ""; results = null }
    Text("장소 넣기", color = c.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold)
    Text("맨 뒤에 들어가요 · 시간과 순서는 왼쪽에서", color = c.soft, fontSize = 20.sp, modifier = Modifier.padding(top = 4.dp))
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            query, { query = it; results = null }, placeholder = { Text("어디로 갈까요?", fontSize = 26.sp, color = c.soft) }, singleLine = true, shape = Corner,
            textStyle = TextStyle(fontSize = 26.sp, color = c.ink), modifier = Modifier.weight(1f),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { search() }),
        )
        Button(
            onClick = ::search, enabled = query.isNotBlank() && !busy, shape = Corner,
            modifier = Modifier.padding(start = 10.dp).heightIn(min = 76.dp),
            colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.onInk, disabledContainerColor = c.disabled, disabledContentColor = c.soft),
        ) { Text(if (busy) "찾는 중" else "찾기", fontSize = 26.sp, fontWeight = FontWeight.Bold) }
    }
    Column(Modifier.weight(1f).padding(top = 8.dp).verticalScroll(rememberScrollState())) {
        results?.forEach { p ->
            Column(Modifier.fillMaxWidth().heightIn(min = 88.dp).clickable { add(p) }.padding(vertical = 10.dp), verticalArrangement = Arrangement.Center) {
                Text(p.name, color = c.ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                p.address.takeIf { it.isNotBlank() }?.let { Text(it, color = c.soft, fontSize = 20.sp, maxLines = 1) }
            }
            HorizontalDivider(color = c.line)
        }
        if (results != null) {
            if (results!!.isEmpty()) Text("찾은 곳이 없어요", color = c.soft, fontSize = 24.sp, modifier = Modifier.padding(top = 12.dp))
            // Always possible: the name alone (갈 때 내비 검색으로 찾음, 기존 규칙).
            OutlinedButton(
                onClick = { add(Place(name = query.trim())) }, shape = Corner, border = BorderStroke(1.dp, c.line),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 76.dp),
            ) { Text("'${query.trim()}' 이름만 넣기", color = c.ink, fontSize = 24.sp, maxLines = 1) }
        }
        added?.let { Text("$it 넣었어요", color = c.accent, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp)) }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalCarColors.current
    Text(
        label, color = if (on) c.onInk else c.ink, fontSize = 24.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier.heightIn(min = 76.dp).background(if (on) c.ink else c.bg, Corner).border(1.dp, if (on) c.ink else c.line, Corner)
            .clickable(onClick = onClick).padding(top = 22.dp),
    )
}
