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
import androidx.compose.runtime.LaunchedEffect
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
fun VoteSheet(groupId: String, eventId: String?, date: String?, nameOf: (String) -> String, onPickPlace: ((Place) -> Unit) -> Unit, onDismiss: () -> Unit, seed: Place? = null, seeds: List<Place> = emptyList()) {
    // 채팅의 장소 여러 곳 → 한 번에 후보로 (A안 2026-11-03).
    val starts = seeds.ifEmpty { listOfNotNull(seed) }.take(5)
    val vm: VoteDartViewModel = viewModel(key = "vote-$groupId-$eventId", factory = voteVmFactory(groupId, eventId, date))
    val votes by vm.list.collectAsStateWithLifecycle()
    val group by vm.group.collectAsStateWithLifecycle()
    val me by vm.me.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val loaded by vm.listLoaded.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(starts.isNotEmpty()) }
    // The list arrives a moment later: open on the list when there are votes, on "새 투표" only when there are none.
    LaunchedEffect(loaded) { if (loaded && starts.isEmpty()) creating = votes.isEmpty() }
    CenterSheet(onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("투표", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.weight(1f))
                TextButton(onClick = { creating = !creating }) { Text(if (creating) "목록" else "새 투표", color = Palette.Navy) }
            }
            message?.let { Text(it, color = Palette.TextStrong, fontSize = 15.sp, modifier = Modifier.clickable { vm.message.value = null }) }
            if (creating) NewVote(onPickPlace = onPickPlace, seeds = starts) { q, opts, places -> vm.create(q, opts, places); creating = false }
            else {
                if (votes.isEmpty()) Text("아직 투표가 없어요", color = Palette.TextSoft, fontSize = 16.sp, modifier = Modifier.padding(vertical = 10.dp))
                votes.forEach { v -> VoteCard(v, me, group?.memberIds?.size ?: 1, isOwner = group?.ownerUid == me || v.createdBy == me, nameOf, onCast = { vm.cast(v.id, it) }, onClose = { vm.close(v.id) }, onAddStop = { vm.addWinnerAsStop(it) }, canAddStop = eventId != null) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) { TextButton(onClick = onDismiss) { Text("닫기", color = Palette.TextSoft) } }
        }
    }
}

@Composable
private fun NewVote(onPickPlace: ((Place) -> Unit) -> Unit, seeds: List<Place> = emptyList(), onCreate: (String, List<String>, List<Place?>) -> Unit) {
    // 5장 1: a stop's "이 장소로 투표" arrives as the first candidate; the chat can bring several.
    var question by remember { mutableStateOf(when { seeds.size == 1 -> "${seeds[0].name} 어때요?"; seeds.size > 1 -> "어디로 갈까요?"; else -> "" }) }
    val options = remember { mutableStateListOf<String>().apply { addAll(seeds.map { it.name }); while (size < 2) add("") } }
    val places = remember { mutableStateListOf<Place?>().apply { addAll(seeds); while (size < 2) add(null) } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(question, { question = it }, placeholder = { Text("점심 뭐 먹지?", fontSize = 17.sp) }, label = { Text("질문") }, singleLine = true, shape = Corner, modifier = Modifier.fillMaxWidth())
        options.forEachIndexed { i, o ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(o, { options[i] = it }, placeholder = { Text("후보 ${i + 1}") }, singleLine = true, shape = Corner, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { onPickPlace { p -> places[i] = p; options[i] = p.name } }, shape = Corner, border = BorderStroke(1.dp, Palette.Line)) { Text(if (places[i] != null) "장소 ✓" else "장소", color = Palette.Navy, fontSize = 15.sp) }
            }
        }
        Row {
            if (options.size < 5) TextButton(onClick = { options.add(""); places.add(null) }) { Text("후보 추가", color = Palette.Navy) }
            Spacer(Modifier.weight(1f))
            Button(onClick = { onCreate(question, options.filter { it.isNotBlank() }, places.filterIndexed { i, _ -> options[i].isNotBlank() }) }, enabled = options.count { it.isNotBlank() } >= 2, shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy)) { Text("투표 열기") }
        }
        Text("과반이 나오면 바로 마감, 다 찍으면 최다 득표로 마감(동점은 둘 다). 후보가 장소면 이긴 곳을 칸으로 넣을 수 있어요.", fontSize = 15.sp, color = Palette.TextSoft)
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
            Text(if (open) "${v.ballots.size}/$members 투표" else "마감", fontSize = 15.sp, color = if (open) Palette.TextStrong else Palette.TextSoft)
        }
        v.options.forEachIndexed { i, o ->
            val won = i in v.winners
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp).clip(Corner).border(if (mine == i || won) 2.dp else 1.dp, if (won || mine == i) Palette.Navy else Palette.Line, Corner)
                    .clickable(enabled = open) { onCast(i) }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(o + if (won) "  · 당선" else "", fontSize = 17.sp, fontWeight = if (won || mine == i) FontWeight.Bold else FontWeight.Normal, color = Palette.TextStrong, modifier = Modifier.weight(1f))
                Text("${counts[i]}표", fontSize = 15.sp, color = Palette.TextSoft)
                if (won && canAddStop && v.places.getOrNull(i) != null) TextButton(onClick = { onAddStop(v.places[i]!!) }) { Text("칸으로", color = Palette.Navy, fontSize = 15.sp) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (open && isOwner) TextButton(onClick = onClose) { Text("지금 마감", color = Palette.TextSoft, fontSize = 15.sp) }
        }
        HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(top = 6.dp))
    }
}

/** 6장: now a full-screen game (DartGame.kt); kept as the entry point the screens already call. */
@Composable
fun DartSheet(groupId: String, eventId: String?, date: String?, nameOf: (String) -> String, onDismiss: () -> Unit) =
    DartGame(groupId, eventId, date, nameOf, onDismiss)

internal fun hasLocationPermission(context: Context) = hasLocation(context)
internal fun lastKnownLocation(context: Context) = lastKnown(context)

private fun hasLocation(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
private fun lastKnown(context: Context): Pair<Double, Double>? {
    if (!hasLocation(context)) return null
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstNotNullOfOrNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }?.let { it.latitude to it.longitude }
}

