package com.htb.carplan.phone.vote

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.htb.carplan.phone.ui.CenterSheet
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.shared.data.Regions
import com.htb.carplan.shared.model.DartScope
import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.model.Vote
import com.htb.carplan.shared.model.VoteStatus

fun voteVmFactory(groupId: String, eventId: String?, date: String?) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = VoteDartViewModel(groupId, eventId, date) as T
}

/** 5장: the group's votes; open ones first. New vote = question + 2~5 options (text or place). */
@Composable
fun VoteSheet(groupId: String, eventId: String?, date: String?, nameOf: (String) -> String, onPickPlace: ((Place) -> Unit) -> Unit, onDismiss: () -> Unit) {
    val vm: VoteDartViewModel = viewModel(key = "vote-$groupId-$eventId", factory = voteVmFactory(groupId, eventId, date))
    val votes by vm.list.collectAsStateWithLifecycle()
    val group by vm.group.collectAsStateWithLifecycle()
    val me by vm.me.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(votes.isEmpty()) }
    CenterSheet(onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("투표", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.weight(1f))
                TextButton(onClick = { creating = !creating }) { Text(if (creating) "목록" else "새 투표", color = Palette.Navy) }
            }
            message?.let { Text(it, color = Palette.Teal, fontSize = 14.sp, modifier = Modifier.clickable { vm.message.value = null }) }
            if (creating) NewVote(onPickPlace = onPickPlace) { q, opts, places -> vm.create(q, opts, places); creating = false }
            else {
                if (votes.isEmpty()) Text("아직 투표가 없어요", color = Palette.TextSoft, fontSize = 15.sp, modifier = Modifier.padding(vertical = 10.dp))
                votes.forEach { v -> VoteCard(v, me, group?.memberIds?.size ?: 1, isOwner = group?.ownerUid == me || v.createdBy == me, nameOf, onCast = { vm.cast(v.id, it) }, onClose = { vm.close(v.id) }, onAddStop = { vm.addWinnerAsStop(it) }, canAddStop = eventId != null) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) { TextButton(onClick = onDismiss) { Text("닫기", color = Palette.TextSoft) } }
        }
    }
}

@Composable
private fun NewVote(onPickPlace: ((Place) -> Unit) -> Unit, onCreate: (String, List<String>, List<Place?>) -> Unit) {
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    val places = remember { mutableStateListOf<Place?>(null, null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(question, { question = it }, placeholder = { Text("점심 뭐 먹지?", fontSize = 17.sp) }, label = { Text("질문") }, singleLine = true, shape = Corner, modifier = Modifier.fillMaxWidth())
        options.forEachIndexed { i, o ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(o, { options[i] = it }, placeholder = { Text("후보 ${i + 1}") }, singleLine = true, shape = Corner, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { onPickPlace { p -> places[i] = p; options[i] = p.name } }, shape = Corner, border = BorderStroke(1.dp, Palette.Line)) { Text(if (places[i] != null) "장소 ✓" else "장소", color = Palette.Navy, fontSize = 13.sp) }
            }
        }
        Row {
            if (options.size < 5) TextButton(onClick = { options.add(""); places.add(null) }) { Text("후보 추가", color = Palette.Navy) }
            Spacer(Modifier.weight(1f))
            Button(onClick = { onCreate(question, options.filter { it.isNotBlank() }, places.filterIndexed { i, _ -> options[i].isNotBlank() }) }, enabled = options.count { it.isNotBlank() } >= 2, shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy)) { Text("투표 열기") }
        }
        Text("과반이 나오면 바로 마감, 다 찍으면 최다 득표로 마감(동점은 둘 다). 후보가 장소면 이긴 곳을 칸으로 넣을 수 있어요.", fontSize = 13.sp, color = Palette.TextSoft)
    }
}

@Composable
private fun VoteCard(v: Vote, me: String?, members: Int, isOwner: Boolean, nameOf: (String) -> String, onCast: (Int) -> Unit, onClose: () -> Unit, onAddStop: (Place) -> Unit, canAddStop: Boolean) {
    val counts = v.options.indices.map { i -> v.ballots.values.count { it == i } }
    val mine = me?.let { v.ballots[it] }
    val open = v.status == VoteStatus.OPEN
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(v.question, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.weight(1f))
            Text(if (open) "${v.ballots.size}/$members 투표" else "마감", fontSize = 13.sp, color = if (open) Palette.Teal else Palette.TextSoft)
        }
        v.options.forEachIndexed { i, o ->
            val won = i in v.winners
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp).clip(Corner).border(if (mine == i || won) 2.dp else 1.dp, if (won) Palette.Teal else if (mine == i) Palette.Navy else Palette.Line, Corner)
                    .clickable(enabled = open) { onCast(i) }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(o + if (won) "  · 당선" else "", fontSize = 16.sp, fontWeight = if (won || mine == i) FontWeight.Bold else FontWeight.Normal, color = Palette.TextStrong, modifier = Modifier.weight(1f))
                Text("${counts[i]}표", fontSize = 14.sp, color = Palette.TextSoft)
                if (won && canAddStop && v.places.getOrNull(i) != null) TextButton(onClick = { onAddStop(v.places[i]!!) }) { Text("칸으로", color = Palette.Navy, fontSize = 13.sp) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (open && isOwner) TextButton(onClick = onClose) { Text("지금 마감", color = Palette.TextSoft, fontSize = 13.sp) }
        }
        HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(top = 6.dp))
    }
}

/** 6장: scope → throw → the group's shared result; retry as often as you like ("n번째 다트!"). */
@Composable
fun DartSheet(groupId: String, eventId: String?, date: String?, nameOf: (String) -> String, onDismiss: () -> Unit) {
    val vm: VoteDartViewModel = viewModel(key = "dart-$groupId-$eventId", factory = voteVmFactory(groupId, eventId, date))
    val dart by vm.dart.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var scope by remember { mutableStateOf(DartScope.NATIONAL) }
    var region by remember { mutableStateOf(Regions.sidoNames.first()) }
    var spinning by remember { mutableStateOf(false) }
    val angle by animateFloatAsState(if (spinning) 1080f else 0f, tween(1200), label = "spin", finishedListener = { spinning = false })
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    fun throwNow() {
        spinning = true
        val here = if (scope == DartScope.NEAR) lastKnown(context) else null
        if (scope == DartScope.NEAR && here == null && !hasLocation(context)) {
            askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)); spinning = false; return
        }
        vm.throwDart(context, scope, here?.first, here?.second, if (scope == DartScope.REGION) region else null)
    }
    CenterSheet(onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("다트 — 동네 뽑기", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.fillMaxWidth())
            Text("여행지가 미정일 때. 던지면 그룹 전원 화면에 같이 떠요.", fontSize = 14.sp, color = Palette.TextSoft, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = scope == DartScope.NATIONAL, onClick = { scope = DartScope.NATIONAL }, shape = Corner, label = { Text("전국") })
                FilterChip(selected = scope == DartScope.NEAR, onClick = { scope = DartScope.NEAR }, shape = Corner, label = { Text("내 위치 100km") })
                FilterChip(selected = scope == DartScope.REGION, onClick = { scope = DartScope.REGION }, shape = Corner, label = { Text("지역") })
            }
            if (scope == DartScope.REGION) Row(Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Regions.sidoNames.forEach { s -> FilterChip(selected = region == s, onClick = { region = s }, shape = Corner, label = { Text(s) }) }
            }
            // The board: a ring with a needle that spins on every throw.
            Box(Modifier.padding(vertical = 18.dp).size(150.dp).clip(CircleShape).border(3.dp, Palette.Line, CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.size(90.dp).clip(CircleShape).border(2.dp, Palette.SheetEdge, CircleShape))
                Box(Modifier.size(14.dp).clip(CircleShape).background(Palette.Teal))
                Box(Modifier.rotate(angle).width(4.dp).height(130.dp)) { Box(Modifier.fillMaxWidth().height(65.dp).background(Palette.Navy)) }
            }
            dart?.let { d ->
                Text(d.resultName, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
                Text("${d.throwCount}번째 다트! · ${nameOf(d.thrownBy)}", fontSize = 14.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 2.dp))
                if (eventId != null) TextButton(onClick = { vm.addDartAsStop(d) }) { Text("이 동네를 여행 칸으로", color = Palette.Navy, fontWeight = FontWeight.SemiBold) }
            }
            message?.let { Text(it, color = Palette.Teal, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp).clickable { vm.message.value = null }) }
            Button(onClick = { throwNow() }, enabled = !spinning, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(52.dp), shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy)) {
                Text(if (dart == null) "던지기" else "다시 던지기", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onDismiss) { Text("닫기", color = Palette.TextSoft) } }
        }
    }
}

private fun hasLocation(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
private fun lastKnown(context: Context): Pair<Double, Double>? {
    if (!hasLocation(context)) return null
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstNotNullOfOrNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }?.let { it.latitude to it.longitude }
}
