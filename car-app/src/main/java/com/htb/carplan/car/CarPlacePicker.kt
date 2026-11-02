package com.htb.carplan.car

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htb.carplan.shared.model.Place
import kotlinx.coroutines.launch

private val Corner = RoundedCornerShape(4.dp)

/**
 * 장소 고르기, 현대 내비 먼저:
 * 1. empty field → the navigation's own 즐겨찾기(집·회사)·최근 목적지, one tap;
 * 2. typed → [내비에서 찾기] opens the Hyundai search, the place the driver starts comes back with coordinates;
 * 3. [목록으로] — Kakao list here, for when the navigation round trip is too much (its results never reach us).
 * [picked] is the matched place (null = only a name so far).
 */
@Composable
internal fun CarPlacePicker(vm: CarHomeViewModel, text: String, onText: (String) -> Unit, picked: Place?, onPick: (Place) -> Unit, hint: String = "장소 (선택)") {
    val c = LocalCarColors.current
    val saved by vm.naviSaved.collectAsStateWithLifecycle()
    val fromNavi by vm.naviPicked.collectAsStateWithLifecycle()
    var waiting by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Place>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val view = LocalView.current
    // CarIME ignores Compose's keyboard hide(): ask InputMethodManager directly.
    fun hideKeyboard() { view.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0); focus.clearFocus() }
    LaunchedEffect(Unit) { vm.refreshNaviSaved() }
    LaunchedEffect(fromNavi) {
        val p = fromNavi ?: return@LaunchedEffect
        if (waiting) { waiting = false; results = null; onPick(p) }
        vm.consumeNaviPick()
    }
    fun pickInNavi() { hideKeyboard(); results = null; waiting = true; vm.pickInNavi(text.trim()) }
    fun listHere() {
        if (text.isBlank() || searching) return
        hideKeyboard(); searching = true
        scope.launch { results = vm.searchPlaces(text); searching = false }
    }

    val field = @Composable { m: Modifier ->
        OutlinedTextField(
            text, { onText(it); results = null }, placeholder = { Text(hint, fontSize = 26.sp, color = c.soft) }, singleLine = true, shape = Corner,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { pickInNavi() }),
            textStyle = TextStyle(fontSize = 26.sp, color = c.ink), modifier = m,
        )
    }
    val buttons = @Composable {
        Button(
            onClick = ::pickInNavi, enabled = picked == null && !waiting, shape = Corner,
            modifier = Modifier.padding(start = 10.dp).heightIn(min = 76.dp),
            colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.onInk, disabledContainerColor = c.disabled, disabledContentColor = c.soft),
        ) { Text("내비에서 찾기", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        if (text.isNotBlank() && picked == null) OutlinedButton(
            onClick = ::listHere, enabled = !searching, shape = Corner, border = BorderStroke(1.dp, c.line),
            modifier = Modifier.padding(start = 10.dp).heightIn(min = 76.dp),
        ) { Text(if (searching) "찾는 중" else "목록으로", color = c.ink, fontSize = 24.sp) }
    }
    // Half screen: the field takes the whole line, the buttons go under it.
    if (LocalCompact.current) {
        field(Modifier.fillMaxWidth())
        Row(Modifier.padding(top = 8.dp).offset(x = (-10).dp), verticalAlignment = Alignment.CenterVertically) { buttons() }
    } else Row(verticalAlignment = Alignment.CenterVertically) { field(Modifier.weight(1f)); buttons() }
    when {
        picked != null -> Text("위치 확인 · ${picked.address.ifBlank { picked.name }}", color = c.accent, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
        waiting -> Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("내비에서 장소를 고르면 여기로 돌아와요", color = c.soft, fontSize = 22.sp, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { waiting = false; vm.stopNaviPick() }, shape = Corner, border = BorderStroke(1.dp, c.line), modifier = Modifier.heightIn(min = 76.dp)) {
                Text("그만", color = c.ink, fontSize = 24.sp)
            }
        }
    }
    val list = results
    if (list != null) {
        list.forEach { p -> PlaceRow(null, p) { onPick(p); results = null } }
        if (list.isEmpty()) Text("찾은 곳이 없어요 · 이대로 넣으면 갈 때 내비가 이름으로 찾아요", color = c.soft, fontSize = 22.sp, modifier = Modifier.padding(top = 10.dp))
    } else if (text.isBlank() && picked == null && !waiting) {
        // What the navigation already knows — 순정 data, one tap, no typing.
        val known = saved.filter { it.place.name.isNotBlank() && (it.place.lat != 0.0 || it.place.lng != 0.0) }.take(5)
        if (known.isNotEmpty()) {
            Text("현대 내비에 있는 곳", color = c.soft, fontSize = 20.sp, modifier = Modifier.padding(top = 12.dp))
            known.forEach { s -> PlaceRow(kindLabel(s.kind), s.place) { onText(s.place.name); onPick(s.place) } }
        }
    }
}

@Composable
private fun PlaceRow(label: String?, p: Place, onClick: () -> Unit) {
    val c = LocalCarColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 88.dp).clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
        label?.let { Text(it, color = c.soft, fontSize = 20.sp, modifier = Modifier.width(72.dp)) }
        Column {
            Text(p.name, color = c.ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            p.address.takeIf { it.isNotBlank() }?.let { Text(it, color = c.soft, fontSize = 20.sp, maxLines = 1) }
        }
    }
    HorizontalDivider(color = c.line)
}

private fun kindLabel(kind: String?) = when (kind) {
    null -> "최근"; "HOME" -> "집"; "OFFICE" -> "회사"; "EV_CHARGING_STATION" -> "충전소"; else -> "즐겨찾기"
}
