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

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        Text("그룹", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
        Text("한 번 만든 그룹은 남겨서 다시 써요", fontSize = 14.sp, color = Palette.TextSoft)
        message?.let { Text(it, color = Palette.Teal, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp).clickable { vm.clearMessage() }) }
        LazyColumn(Modifier.weight(1f).padding(top = 12.dp)) {
            items(groups.filterNot { it.solo }, key = { it.id }) { g -> GroupRow(g, vm.nameOf(g.ownerUid), g.memberIds.size) { vm.open(g.id) } }
            item {
                if (groups.none { !it.solo }) Text("아직 그룹이 없어요. 새 그룹을 만들거나 코드로 들어와요.", color = Palette.TextSoft, fontSize = 15.sp, modifier = Modifier.padding(vertical = 12.dp))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { creating = true }, modifier = Modifier.weight(1f).height(50.dp), shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy)) { Text("새 그룹", fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
                    OutlinedButton(onClick = { scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setPrompt("차 화면의 QR을 비춰 주세요")) }, modifier = Modifier.weight(1f).height(50.dp), shape = Corner, border = BorderStroke(1.dp, Palette.Line)) { Text("차 QR 찍기", color = Palette.Navy, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
                }
                SectionTitle("코드로 참여")
                CodeRow(code, { code = it }, "초대 코드 6자리", "참여") { InviteInbox.offerCode(code); code = "" }

                SectionTitle("친구")
                Text("내 친구 코드  ${me?.friendCode ?: "…"}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextStrong)
                Text("같은 그룹에 있었던 사람은 자동으로 친구 후보예요. 코드로도 추가할 수 있어요.", fontSize = 13.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
                CodeRow(friendCode, { friendCode = it }, "친구 코드", "추가") { vm.addFriendByCode(friendCode); friendCode = "" }
                if (friends.isEmpty()) Text("아직 친구가 없어요", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 8.dp))
                friends.forEach { uid ->
                    Text(vm.nameOf(uid), fontSize = 16.sp, color = Palette.TextStrong, modifier = Modifier.padding(vertical = 8.dp))
                    HorizontalDivider(color = Palette.Line)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (creating) NameDialog(title = "새 그룹", hint = "예: 대학 동기, 우리 가족", confirm = "만들기", onDismiss = { creating = false }) { name ->
        creating = false
        vm.createGroup(name) { vm.open(it) }
    }
}

@Composable
private fun SectionTitle(text: String) = Text(text, fontSize = 14.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 22.dp, bottom = 6.dp))

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
private fun GroupRow(g: Group, owner: String, members: Int, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(g.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
        Text("${members}명 · 그룹장 $owner", fontSize = 14.sp, color = Palette.TextSoft)
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
                Text("${g.memberIds.size}명 · 그룹장 ${vm.nameOf(g.ownerUid)}" + if (isOwner) " · 이름 바꾸려면 누르기" else "", fontSize = 13.sp, color = Palette.TextSoft)
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
                    ) { Text("초대 링크 보내기", fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
                    OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f).height(50.dp), shape = Corner, border = BorderStroke(1.dp, Palette.Line)) {
                        Text("코드  ${g.inviteCode}", color = Palette.TextStrong, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
                Text("차 안에서는 차 화면의 QR로 들어와요", fontSize = 13.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 6.dp))
                val invitable = friends.filterNot { it in g.memberIds }
                if (invitable.isNotEmpty()) {
                    Text("친구 초대", fontSize = 13.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    invitable.forEach { uid ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(vm.nameOf(uid), fontSize = 16.sp, color = Palette.TextStrong, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.inviteFriend(g.id, uid) }) { Text("초대", color = Palette.Navy, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }

                SectionTitle("멤버")
            }
            items(g.memberIds, key = { it }) { uid ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(vm.nameOf(uid) + if (uid == g.ownerUid) "  · 그룹장" else "", fontSize = 16.sp, color = Palette.TextStrong, modifier = Modifier.weight(1f))
                    if (isOwner && uid != g.ownerUid) TextButton(onClick = { vm.removeMember(g.id, uid) }) { Text("내보내기", color = Palette.TextSoft) }
                }
                HorizontalDivider(color = Palette.Line)
            }
            item {
                SectionTitle("같이 정하기")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { voteOpen = true }, modifier = Modifier.weight(1f).height(50.dp), shape = Corner, border = BorderStroke(1.dp, Palette.Line)) { Text("투표", color = Palette.Navy, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
                    OutlinedButton(onClick = { dartOpen = true }, modifier = Modifier.weight(1f).height(50.dp), shape = Corner, border = BorderStroke(1.dp, Palette.Line)) { Text("다트 (동네 뽑기)", color = Palette.Navy, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
                }
                SectionTitle("여행")
                Button(onClick = { newTrip = true }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy)) { Text("새 여행 만들기", fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
                if (trips.isEmpty()) Text("아직 여행이 없어요 — 빈 도화지에서 시작해요", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 10.dp))
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
        Text((if (e.startDate == e.endDate || e.endDate.isEmpty()) e.startDate else "${e.startDate} – ${e.endDate}") + if (past) "  · 지난 여행" else "", fontSize = 13.sp, color = Palette.TextSoft)
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
                TextButton(onClick = onDismiss) { Text("취소", color = Palette.TextSoft, fontSize = 16.sp) }
                Button(onClick = { onConfirm(name) }, enabled = name.isNotBlank(), shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy)) { Text(confirm, fontSize = 16.sp) }
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
    LaunchedEffect(c) { group = vm.groupForCode(c); missing = group == null }
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
                TextButton(onClick = { InviteInbox.clear() }) { Text("닫기", color = Palette.TextSoft, fontSize = 16.sp) }
                if (group != null) Button(onClick = { vm.joinByCode(c) { InviteInbox.clear(); onJoined(it) } }, shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy)) { Text("참여하기", fontSize = 16.sp) }
            }
        }
    }
}
