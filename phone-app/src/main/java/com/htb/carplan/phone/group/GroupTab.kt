package com.htb.carplan.phone.group

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.htb.carplan.phone.calendar.QuickAddSheet
import com.htb.carplan.phone.trip.TripDayScreen
import com.htb.carplan.phone.trip.TripRef
import com.htb.carplan.phone.ui.CenterSheet
import com.htb.carplan.phone.vote.DartSheet
import com.htb.carplan.phone.vote.VoteSheet
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.shared.data.CalendarEvent
import com.htb.carplan.shared.data.CalendarRepository
import com.htb.carplan.shared.data.InviteLinks
import com.htb.carplan.shared.model.BuiltInCategory
import com.htb.carplan.shared.model.Group
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 4장 그룹 탭: my groups, 새 그룹, 코드/QR로 참여, 친구. Tapping a group opens [GroupScreen]. */
@Composable
fun GroupTab(vm: GroupViewModel = viewModel()) {
    val groups by vm.myGroups.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val me by vm.me.collectAsStateWithLifecycle()
    val friends by vm.friends.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    vm.names.collectAsStateWithLifecycle().value
    var creating by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var friendCode by remember { mutableStateOf("") }
    var openTrip by rememberSaveable { mutableStateOf<List<String>?>(null) }

    openTrip?.let { (g, e, d) ->
        TripDayScreen(TripRef(g, e, d), onBack = { openTrip = null })
        return
    }
    selectedId?.let { id ->
        GroupScreen(vm, id, onBack = { vm.open(null) }, onOpenTrip = { g, e, d -> openTrip = listOf(g, e, d) })
        return
    }

    val scan = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let { InviteInbox.offer(it) } }
    val groupTrips by vm.groupTrips.collectAsStateWithLifecycle()
    var plus by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    var addingFriend by remember { mutableStateOf(false) }
    val today = LocalDate.now().toString()

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("그룹", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.weight(1f))
            // One [+] for everything that is not "open a group": make · join by code · scan the car QR · add a friend.
            Box {
                IconButton(onClick = { plus = true }, modifier = Modifier.background(Palette.Navy, Corner)) { Icon(Icons.Filled.Add, "추가", tint = androidx.compose.ui.graphics.Color.White) }
                DropdownMenu(plus, onDismissRequest = { plus = false }) {
                    DropdownMenuItem({ Text("새 그룹 만들기") }, onClick = { plus = false; creating = true })
                    DropdownMenuItem({ Text("초대 코드로 참여") }, onClick = { plus = false; joining = true })
                    DropdownMenuItem({ Text("차 QR 찍기") }, onClick = { plus = false; scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setPrompt("차 화면의 QR을 비춰 주세요")) })
                    HorizontalDivider(color = Palette.Line)
                    DropdownMenuItem({ Text("친구 추가 (코드)") }, onClick = { plus = false; addingFriend = true })
                }
            }
        }
        message?.let { Text(it, color = Palette.Teal, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp).clickable { vm.clearMessage() }) }
        val shared = groups.filterNot { it.solo }
        LazyColumn(Modifier.weight(1f).padding(top = 10.dp)) {
            items(shared, key = { it.id }) { g ->
                val t = groupTrips[g.id]
                val tripLine = t?.let { ce ->
                    val d = LocalDate.parse(ce.event.startDate)
                    (if (ce.event.endDate >= today) "다음 여행" else "지난 여행") + "  ${d.monthValue}/${d.dayOfMonth} ${ce.event.title}"
                }
                val others = g.memberIds.filter { it != me?.uid }
                val who = when {
                    others.isEmpty() -> "나 혼자"
                    others.size == 1 -> vm.nameOf(others[0])
                    else -> "${vm.nameOf(others[0])} 외 ${others.size - 1}명"
                }
                GroupRow(g, who, tripLine) { vm.open(g.id) }
            }
            item {
                if (shared.isEmpty()) Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("아직 그룹이 없어요", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextStrong)
                    Text("오른쪽 위 [+]에서 만들거나 초대 코드로 들어와요", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 4.dp))
                }
                groups.firstOrNull { it.solo }?.let { solo ->
                    Spacer(Modifier.height(16.dp))
                    GroupRow(solo, "혼자 쓰는 일정 · 차량 관리", null) { vm.open(solo.id) }
                }
                Text("내 친구 코드  ${me?.friendCode ?: "…"}  ·  친구 ${friends.size}명", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 22.dp, bottom = 24.dp))
            }
        }
    }

    if (creating) NameDialog(title = "새 그룹", hint = "예: 대학 동기, 우리 가족", confirm = "만들기", onDismiss = { creating = false }) { name ->
        creating = false
        vm.createGroup(name) { vm.open(it) }
    }
    if (joining) CodeDialog(title = "초대 코드로 참여", hint = "초대 코드 6자리", action = "참여", onDismiss = { joining = false }) { joining = false; InviteInbox.offerCode(it) }
    if (addingFriend) CodeDialog(title = "친구 추가", hint = "친구 코드 6자리", action = "추가", note = "내 친구 코드는 ${me?.friendCode ?: "…"} 예요. 같은 그룹에 있었던 사람은 자동으로 친구예요.", onDismiss = { addingFriend = false }) { addingFriend = false; vm.addFriendByCode(it) }
}

/** A 6-character code in its own small sheet (join / friend). */
@Composable
private fun CodeDialog(title: String, hint: String, action: String, note: String? = null, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    com.htb.carplan.phone.ui.CenterSheet(onDismiss = onDismiss) {
        Column {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
            note?.let { Text(it, fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(12.dp))
            CodeRow(code, { code = it }, hint, action) { onConfirm(code) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) = Text(text, fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 22.dp, bottom = 6.dp))

@Composable
private fun CodeRow(value: String, onChange: (String) -> Unit, hint: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value, { onChange(it.uppercase().take(6)) }, placeholder = { Text(hint) }, singleLine = true, shape = Corner,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.weight(1f),
        )
        Button(onClick = onAction, enabled = value.length >= 4, shape = Corner, modifier = Modifier.height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy)) { Text(action, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun GroupRow(g: Group, who: String, tripLine: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(g.name, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
            Text(who, fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 2.dp))
            tripLine?.let { Text(it, fontSize = 16.sp, color = if (it.startsWith("다음")) Palette.Teal else Palette.TextSoft, fontWeight = if (it.startsWith("다음")) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(top = 4.dp)) }
        }
        Text("›", fontSize = 22.sp, color = Palette.TextSoft)
    }
    HorizontalDivider(color = Palette.Line)
}

/** One group: members (그룹장 can remove), 초대 (link · code · friends), trips, 새 여행, 나가기. */
@Composable
fun GroupScreen(vm: GroupViewModel, groupId: String, onBack: () -> Unit, onOpenTrip: (String, String, String) -> Unit) {
    val group by vm.selected.collectAsStateWithLifecycle()
    val trips by vm.trips.collectAsStateWithLifecycle()
    val friends by vm.friends.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    vm.names.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var newTrip by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var voteOpen by remember { mutableStateOf(false) }
    var dartOpen by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    val g = group ?: return
    val isOwner = g.ownerUid == session?.uid

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = Palette.TextStrong) }
            Column(Modifier.weight(1f).clickable(enabled = isOwner) { renaming = true }) {
                Text(g.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
                Text("${g.memberIds.size}명 · 그룹장 ${vm.nameOf(g.ownerUid)}" + if (isOwner) " · 이름 바꾸려면 누르기" else "", fontSize = 15.sp, color = Palette.TextSoft)
            }
            TextButton(onClick = { vm.leave(g.id); onBack() }) { Text("나가기", color = Palette.TextSoft) }
        }
        HorizontalDivider(color = Palette.Line)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            item {
                SectionTitle("초대")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, "${g.name} 그룹에 들어와요 — ${InviteLinks.url(g.inviteCode)} (코드 ${g.inviteCode})")
                            context.startActivity(Intent.createChooser(send, "초대 링크 보내기"))
                        },
                        modifier = Modifier.weight(1f).height(50.dp), shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy),
                    ) { Text("초대 링크 보내기", fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
                    var copied by remember(g.id) { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = {
                            (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                .setPrimaryClip(android.content.ClipData.newPlainText("초대 코드", g.inviteCode))
                            copied = true
                        },
                        modifier = Modifier.weight(1f).height(50.dp), shape = Corner, border = BorderStroke(1.dp, Palette.Line),
                    ) {
                        Text(if (copied) "복사했어요" else "코드  ${g.inviteCode}", color = Palette.TextStrong, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
                    if (copied) LaunchedEffect(Unit) { kotlinx.coroutines.delay(2000); copied = false }
                }
                Text("차 안에서는 차 화면의 QR로 들어와요", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 6.dp))
                val invitable = friends.filterNot { it in g.memberIds }
                if (invitable.isNotEmpty()) {
                    Text("친구 초대", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    invitable.forEach { uid ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(vm.nameOf(uid), fontSize = 17.sp, color = Palette.TextStrong, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.inviteFriend(g.id, uid) }) { Text("초대", color = Palette.Navy, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }

                SectionTitle("멤버")
            }
            items(g.memberIds, key = { it }) { uid ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(vm.nameOf(uid) + if (uid == g.ownerUid) "  · 그룹장" else "", fontSize = 17.sp, color = Palette.TextStrong, modifier = Modifier.weight(1f))
                    if (isOwner && uid != g.ownerUid) TextButton(onClick = { vm.removeMember(g.id, uid) }) { Text("내보내기", color = Palette.TextSoft) }
                }
                HorizontalDivider(color = Palette.Line)
            }
            item {
                SectionTitle("같이 정하기")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { voteOpen = true }, modifier = Modifier.weight(1f).height(50.dp), shape = Corner, border = BorderStroke(1.dp, Palette.Line)) { Text("투표", color = Palette.Navy, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) }
                    OutlinedButton(onClick = { dartOpen = true }, modifier = Modifier.weight(1f).height(50.dp), shape = Corner, border = BorderStroke(1.dp, Palette.Line)) { Text("다트 (동네 뽑기)", color = Palette.Navy, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) }
                }
                SectionTitle("여행")
                Button(onClick = { newTrip = true }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy)) { Text("새 여행 만들기", fontWeight = FontWeight.SemiBold, fontSize = 17.sp) }
                if (trips.isEmpty()) Text("아직 여행이 없어요 — 빈 도화지에서 시작해요", fontSize = 16.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 10.dp))
            }
            items(trips, key = { it.event.id }) { t -> TripRow(t) { onOpenTrip(g.id, t.event.id, t.event.startDate) } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (newTrip) {
        // 3장 flow with this group fixed: title + dates, then straight into the rail.
        val calendar = remember { CalendarRepository() }
        QuickAddSheet(
            initialDate = LocalDate.now(),
            categories = emptyList(),
            onAddCategory = { _, _, _ -> },
            fixedCategoryId = BuiltInCategory.TRIP.id,
            onSave = { e, _ ->
                newTrip = false
                scope.launch {
                    runCatching { calendar.addEvent(g.id, e.copy(createdBy = session?.uid.orEmpty())) }
                        .onSuccess { onOpenTrip(g.id, it, e.startDate) }
                }
            },
            onDismiss = { newTrip = false },
        )
    }
    if (voteOpen) VoteSheet(g.id, null, null, nameOf = vm::nameOf, onPickPlace = { }, onDismiss = { voteOpen = false })
    if (dartOpen) DartSheet(g.id, null, null, nameOf = vm::nameOf, onDismiss = { dartOpen = false })
    if (renaming) NameDialog(title = "그룹 이름", hint = g.name, confirm = "저장", onDismiss = { renaming = false }) { renaming = false; vm.rename(g.id, it) }
}

@Composable
private fun TripRow(t: CalendarEvent, onClick: () -> Unit) {
    val e = t.event
    val past = LocalDate.parse(e.endDate.ifEmpty { e.startDate }) < LocalDate.now()
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(e.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (past) Palette.TextSoft else Palette.TextStrong)
        Text((if (e.startDate == e.endDate || e.endDate.isEmpty()) e.startDate else "${e.startDate} – ${e.endDate}") + if (past) "  · 지난 여행" else "", fontSize = 15.sp, color = Palette.TextSoft)
    }
    HorizontalDivider(color = Palette.Line)
}

@Composable
private fun NameDialog(title: String, hint: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    CenterSheet(onDismiss = onDismiss) {
        Column {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
            OutlinedTextField(name, { name = it }, placeholder = { Text(hint, fontSize = 17.sp) }, singleLine = true, shape = Corner, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("취소", color = Palette.TextSoft, fontSize = 17.sp) }
                Button(onClick = { onConfirm(name) }, enabled = name.isNotBlank(), shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy)) { Text(confirm, fontSize = 17.sp) }
            }
        }
    }
}

/**
 * "참여할까요?" — shown anywhere in the app when an invite code arrives (link, QR, typed).
 * Looks the group up first so the person sees its name before joining.
 */
@Composable
fun JoinDialog(vm: GroupViewModel, onJoined: (String) -> Unit) {
    val code by InviteInbox.code.collectAsStateWithLifecycle()
    val c = code ?: return
    var group by remember(c) { mutableStateOf<Group?>(null) }
    var missing by remember(c) { mutableStateOf(false) }
    // The same link/QR can carry a friend code (car QR on a day without a trip, 정원 2026-10-31).
    var friend by remember(c) { mutableStateOf<com.htb.carplan.shared.model.User?>(null) }
    LaunchedEffect(c) {
        group = vm.groupForCode(c)
        if (group == null) friend = vm.userForFriendCode(c)
        missing = group == null && friend == null
    }
    friend?.let { f ->
        CenterSheet(onDismiss = { InviteInbox.clear() }) {
            Column {
                Text("친구 추가", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
                Text("${f.name.ifBlank { "이 사람" }}님과 친구가 될까요? 다음 일정부터 이름만 눌러 초대할 수 있어요.", fontSize = 17.sp, color = Palette.ChipText, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { InviteInbox.clear() }) { Text("닫기", color = Palette.TextSoft, fontSize = 17.sp) }
                    Button(onClick = { vm.addFriendByCode(c); InviteInbox.clear() }, shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy)) { Text("친구 추가", fontSize = 17.sp) }
                }
            }
        }
        return
    }
    CenterSheet(onDismiss = { InviteInbox.clear() }) {
        Column {
            Text(if (missing) "초대를 찾지 못했어요" else "그룹 초대", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
            Text(
                when {
                    missing -> "코드 $c 에 맞는 그룹이 없어요. 코드를 다시 확인해 주세요."
                    group == null -> "확인 중…"
                    else -> "${group!!.name} · ${group!!.memberIds.size}명 · 그룹장 ${vm.nameOf(group!!.ownerUid)}"
                },
                fontSize = 17.sp, color = Palette.ChipText, modifier = Modifier.padding(top = 10.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { InviteInbox.clear() }) { Text("닫기", color = Palette.TextSoft, fontSize = 17.sp) }
                if (group != null) Button(onClick = { vm.joinByCode(c) { InviteInbox.clear(); onJoined(it) } }, shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy)) { Text("참여하기", fontSize = 17.sp) }
            }
        }
    }
}
