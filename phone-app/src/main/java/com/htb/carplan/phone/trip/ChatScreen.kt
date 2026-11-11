package com.htb.carplan.phone.trip

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.shared.data.ChatRepository
import com.htb.carplan.shared.model.ChatKind
import com.htb.carplan.shared.model.ChatMessage
import com.htb.carplan.shared.model.Place
import kotlinx.coroutines.launch

/** When I last looked at a group's chat — for the "새 글 n" on the trip screen (this phone only). */
object ChatSeen {
    private fun prefs(c: Context) = c.getSharedPreferences("chat_seen", Context.MODE_PRIVATE)
    fun get(c: Context, groupId: String): Long = prefs(c).getLong(groupId, 0L)
    fun mark(c: Context, groupId: String) = prefs(c).edit().putLong(groupId, System.currentTimeMillis()).apply()
    fun unread(lines: List<ChatMessage>, me: String?, seen: Long) =
        lines.count { it.uid != me && (it.createdAt?.toDate()?.time ?: 0L) > seen }
}

/** What the chat can ask the trip screen to do — 우리 앱 기능을 채팅에서 바로 (A안 2026-11-03). */
class ChatActions(
    /** null while the trip is locked (운전자 폰 주행 중). */
    val addStop: ((Place, memo: String) -> Unit)?,
    /** True when this place is already a live stop of the day shown — then [칸으로] reads "일정에 있어요". */
    val inDay: (Place) -> Boolean = { false },
    /** Open the place search (optionally with words already typed) and hand back the pick. */
    val pickPlace: (query: String, onPick: (Place) -> Unit) -> Unit,
    /** Open a vote, with these places as the first candidates (empty = blank vote / see the votes). */
    val openVote: (List<Place>) -> Unit,
    val openDart: () -> Unit,
    val openPickup: () -> Unit,
    /** Close the chat and show that day's rail. */
    val showDay: (date: String?) -> Unit,
    /** Close the chat, show that day and open 내보내기. */
    val exportDay: (date: String?) -> Unit,
)

/**
 * 여행별 채팅방 (팀장 2026-11-03 A안). Words plus the app's cards — 다트·장소 ([칸으로]·[투표 후보로]),
 * 투표 ([투표하기]·이긴 곳 [칸으로]), 칸 변경·코멘트 ([레일에서 보기]), 도착 ([사진·한 줄]), 하루 끝 ([하루 카드]).
 * [+] opens 장소·투표·다트·픽업 right from the chat. Full screen over the trip; the car never shows it.
 */
@Composable
fun ChatScreen(
    groupId: String, eventId: String?, date: String?, title: String, me: String?, nameOf: (String?) -> String,
    actions: ChatActions, onDismiss: () -> Unit,
) {
    val repo = remember { ChatRepository() }
    val lines by remember(groupId) { repo.observe(groupId) }.collectAsStateWithLifecycle(emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var added by remember { mutableStateOf(setOf<String>()) }
    var plus by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    // Newest at the bottom; follow it as lines arrive, and count them as read.
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.animateScrollToItem(lines.lastIndex); ChatSeen.mark(context, groupId) }
    fun send() {
        val t = text.trim(); if (t.isEmpty() || me == null) return
        text = ""
        scope.launch { runCatching { repo.send(groupId, me, t, eventId) } }
    }
    fun postPlace(p: Place) { if (me != null) scope.launch { runCatching { repo.sharePlace(groupId, me, p, eventId, date) } } }
    // Places talked about since the last vote opened → one tap makes them a vote.
    val lastVote = lines.indexOfLast { it.kind == ChatKind.VOTE_OPEN }
    val talkedPlaces = lines.drop(lastVote + 1).filter { it.kind == ChatKind.PLACE || it.kind == ChatKind.DART }
        .mapNotNull { it.place }.distinctBy { it.name }.takeLast(5)

    // A screen over the trip (BackScreen in TripDayScreen), not a separate window: back closes the chat and only the chat
    // (QA 2026-11-04: as a Dialog the first back did nothing and the second closed the trip too).
    Box(Modifier.fillMaxSize()) {
        run {
            Column(Modifier.fillMaxSize().background(Palette.Background).systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = Palette.TextStrong) }
                    Column(Modifier.weight(1f)) {
                        Text("채팅", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
                        Text(title, fontSize = 15.sp, color = Palette.TextSoft, maxLines = 1)
                    }
                    androidx.compose.material3.TextButton(onClick = { actions.showDay(date) }) { Text("레일 보기", fontSize = 15.sp, color = Palette.Navy, fontWeight = FontWeight.SemiBold) }
                }
                HorizontalDivider(color = Palette.Line)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (lines.isEmpty()) item {
                        Text("아직 아무 말도 없어요. 아래 [+]로 장소·투표·다트를 바로 보낼 수 있어요", fontSize = 16.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 24.dp))
                    }
                    itemsIndexed(lines, key = { _, it -> it.id }) { i, m ->
                        // A new day starts with its date (디자인 평가 2026-11-04: one long list with no "when").
                        val day = dayOf(m)
                        if (day != null && day != lines.getOrNull(i - 1)?.let(::dayOf)) Text(day, fontSize = 14.sp, color = Palette.TextSoft,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = if (i == 0) 0.dp else 12.dp, bottom = 4.dp))
                        if (m.kind in NOTICES) NoticeLine(m, nameOf = { if (it == me) "나" else nameOf(it) }, mine = m.uid == me, actions = actions)
                        else if (m.kind == ChatKind.TEXT) TextLine(
                            m, mine = m.uid == me, nameOf = nameOf,
                            // 말풍선 길게 → 그 글로 장소 찾기 (누를 때만 검색: API 한도).
                            onFindPlace = { actions.pickPlace(m.text) { p -> postPlace(p) } },
                        )
                        else CardLine(
                            m, nameOf = { if (it == me) "나" else nameOf(it) }, mine = m.uid == me,
                            added = m.id in added || (m.place?.let(actions.inDay) == true),
                            onAdd = if (actions.addStop != null && m.place != null && m.kind != ChatKind.ARRIVED) ({
                                val memo = when (m.kind) { ChatKind.DART -> "다트"; ChatKind.VOTE_CLOSED -> "투표"; else -> "채팅" }
                                actions.addStop.invoke(m.place!!, memo); added = added + m.id
                            }) else null,
                            actions = actions,
                        )
                    }
                }
                // Two or more places in the talk → [이 장소들로 투표].
                if (talkedPlaces.size >= 2) Row(
                    Modifier.fillMaxWidth().border(1.dp, Palette.Line).clickable { actions.openVote(talkedPlaces) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("이야기한 장소 ${talkedPlaces.size}곳 · " + talkedPlaces.joinToString(", ") { it.name }, fontSize = 15.sp, color = Palette.TextSoft, maxLines = 1, modifier = Modifier.weight(1f))
                    Text("투표로 정하기", fontSize = 16.sp, color = Palette.Navy, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                }
                HorizontalDivider(color = Palette.Line)
                // [+]: 우리 앱 기능을 채팅에서 바로.
                if (plus) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlusItem("장소 보내기") { plus = false; actions.pickPlace("") { p -> postPlace(p) } }
                    PlusItem("투표 열기") { plus = false; actions.openVote(emptyList()) }
                    PlusItem("다트") { plus = false; actions.openDart() }
                    PlusItem("픽업") { plus = false; actions.openPickup() }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { plus = !plus }, shape = Corner, border = BorderStroke(1.dp, if (plus) Palette.Navy else Palette.Line),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        modifier = Modifier.padding(end = 8.dp).height(52.dp).widthIn(min = 52.dp),
                    ) { Text(if (plus) "×" else "+", fontSize = 24.sp, color = Palette.Navy) }
                    TextField(
                        text, { text = it }, placeholder = { Text("메시지", fontSize = 17.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 17.sp), singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                        shape = Corner,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Palette.InputBg, unfocusedContainerColor = Palette.InputBg, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = ::send, enabled = text.isNotBlank(), shape = Corner,
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy, contentColor = Color.White),
                        modifier = Modifier.padding(start = 8.dp).height(52.dp),
                    ) { Text("보내기", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

@Composable
private fun PlusItem(label: String, onClick: () -> Unit) {
    Text(
        label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.Navy,
        modifier = Modifier.border(1.dp, Palette.Line, Corner).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

private fun dayOf(m: ChatMessage): String? = m.createdAt?.toDate()?.let { android.text.format.DateFormat.format("M월 d일 EEEE", it).toString() }

/** What happened, not something to act on: a quiet centred line instead of a card (위계 3단계: 말 / 할 일 카드 / 알림). */
private val NOTICES = setOf(ChatKind.CHANGE, ChatKind.COMMENT, ChatKind.PICKUP, ChatKind.ARRIVED)

@Composable
private fun NoticeLine(m: ChatMessage, nameOf: (String?) -> String, mine: Boolean, actions: ChatActions) {
    val who = nameOf(m.uid)
    val (head, body, link) = when (m.kind) {
        ChatKind.CHANGE -> Triple("${who}${if (mine) "가" else "님이"} 일정을 바꿨어요", m.lines.take(3) + listOfNotNull((m.lines.size - 3).takeIf { it > 0 }?.let { "외 ${it}건" }), "레일에서 보기" to { actions.showDay(m.date) })
        ChatKind.COMMENT -> Triple("$who · ${m.lines.firstOrNull() ?: "칸"}에 코멘트", listOf("“${m.text}”"), "레일에서 보기" to { actions.showDay(m.date) })
        ChatKind.PICKUP -> Triple(m.text, m.lines, "픽업 보기" to { actions.openPickup() })
        else -> Triple(m.text, m.lines, "사진·한 줄 남기기" to { actions.showDay(m.date) })
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$head · ${hhmm(m)}", fontSize = 14.sp, color = Palette.TextSoft, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        body.forEach { Text(it, fontSize = 15.sp, color = Palette.TextStrong, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2) }
        Text(link.first, fontSize = 14.sp, color = Palette.Navy, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 2.dp).clip(Corner).clickable(onClick = link.second).padding(horizontal = 8.dp, vertical = 6.dp))
    }
}

private fun hhmm(m: ChatMessage) = m.createdAt?.toDate()?.let { android.text.format.DateFormat.format("HH:mm", it).toString() } ?: ""

/** Mine: black, on the right. Others: white with a line, name above. Long press → find a place in the words. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TextLine(m: ChatMessage, mine: Boolean, nameOf: (String?) -> String, onFindPlace: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (!mine) Text(nameOf(m.uid), fontSize = 14.sp, color = Palette.TextSoft, modifier = Modifier.padding(bottom = 2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            if (mine) Text(hhmm(m), fontSize = 13.sp, color = Palette.TextSoft, modifier = Modifier.padding(end = 6.dp))
            Box {
                Text(
                    m.text, fontSize = 17.sp, color = if (mine) Color.White else Palette.TextStrong,
                    modifier = Modifier.widthIn(max = 300.dp)
                        .then(if (mine) Modifier.background(Palette.Navy, Corner) else Modifier.border(1.dp, Palette.SheetEdge, Corner))
                        .combinedClickable(onClick = {}, onLongClick = { menu = true })
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
                androidx.compose.material3.DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    androidx.compose.material3.DropdownMenuItem({ Text("이 글로 장소 찾기", fontSize = 16.sp) }, onClick = { menu = false; onFindPlace() })
                }
            }
            if (!mine) Text(hhmm(m), fontSize = 13.sp, color = Palette.TextSoft, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** A card: the whole width, a small label, the headline, and the actions that fit it. */
@Composable
private fun CardLine(m: ChatMessage, nameOf: (String?) -> String, mine: Boolean, added: Boolean, onAdd: (() -> Unit)?, actions: ChatActions) {
    val who = nameOf(m.uid)
    val label = when (m.kind) {
        ChatKind.DART -> if (mine) "다트 · 내가 올림" else "다트 · ${who}님이 올림"
        ChatKind.PLACE -> if (m.lines.isNotEmpty()) "${who}${if (mine) "가" else "님이"} 물어봐요" else "장소 · $who"
        ChatKind.VOTE_OPEN -> "투표가 열렸어요 · $who"
        ChatKind.VOTE_CLOSED -> "투표 마감"
        ChatKind.CHANGE -> "${who}${if (mine) "가" else "님이"} 일정을 바꿨어요"
        ChatKind.PICKUP -> "픽업"
        ChatKind.ARRIVED -> "도착"
        ChatKind.DAY_DONE -> "하루 끝"
        ChatKind.COMMENT -> "${who} · 칸 코멘트"
        ChatKind.TEXT -> ""
    }
    Column(Modifier.fillMaxWidth().border(1.dp, Palette.SheetEdge, Corner).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, color = Palette.TextSoft, modifier = Modifier.weight(1f))
            Text(hhmm(m), fontSize = 13.sp, color = Palette.TextSoft)
        }
        when (m.kind) {
            ChatKind.DART -> {
                Text(m.text.split(" ").first(), fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 4.dp))
                Text(m.text.split(" ").drop(1).joinToString(" ").ifBlank { m.text }, fontSize = 24.sp, fontWeight = FontWeight.Black, color = Palette.TextStrong)
            }
            ChatKind.PLACE -> {
                m.lines.firstOrNull()?.let { Text(it, fontSize = 17.sp, color = Palette.TextStrong, modifier = Modifier.padding(top = 4.dp)) }
                Text(m.place?.name ?: m.text, fontSize = 22.sp, fontWeight = FontWeight.Black, color = Palette.Teal, modifier = Modifier.padding(top = 2.dp))
                m.place?.address?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 15.sp, color = Palette.TextSoft, maxLines = 1) }
            }
            ChatKind.VOTE_OPEN -> {
                Text(m.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.padding(top = 4.dp))
                m.lines.forEach { Text("· $it", fontSize = 16.sp, color = Palette.TextStrong) }
            }
            ChatKind.VOTE_CLOSED -> {
                Text(m.text, fontSize = 16.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 4.dp))
                Text(if (m.lines.isEmpty()) "결과 없음" else m.lines.joinToString(" · "), fontSize = 22.sp, fontWeight = FontWeight.Black, color = Palette.TextStrong)
            }
            ChatKind.CHANGE -> m.lines.forEach { Text(it, fontSize = 17.sp, color = Palette.TextStrong, modifier = Modifier.padding(top = 2.dp)) }
            ChatKind.COMMENT -> {
                m.lines.firstOrNull()?.let { Text(it, fontSize = 15.sp, color = Palette.TextStrong, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp)) }
                Text(m.text, fontSize = 18.sp, color = Palette.TextStrong)
            }
            ChatKind.PICKUP, ChatKind.ARRIVED, ChatKind.DAY_DONE -> {
                Text(m.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.padding(top = 4.dp))
                m.lines.forEach { Text(it, fontSize = 15.sp, color = Palette.TextSoft) }
            }
            ChatKind.TEXT -> {}
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (m.kind) {
                ChatKind.VOTE_OPEN -> CardButton("투표하기") { actions.openVote(emptyList()) }
                ChatKind.CHANGE, ChatKind.COMMENT -> CardButton("레일에서 보기") { actions.showDay(m.date) }
                ChatKind.PICKUP -> CardButton("픽업 보기") { actions.openPickup() }
                ChatKind.ARRIVED -> CardButton("사진·한 줄 남기기") { actions.showDay(m.date) }
                ChatKind.DAY_DONE -> CardButton("하루 카드 만들기") { actions.exportDay(m.date) }
                else -> {}
            }
            if (onAdd != null && (m.kind == ChatKind.DART || m.kind == ChatKind.PLACE || m.kind == ChatKind.VOTE_CLOSED)) {
                CardButton(if (added) "일정에 있어요" else "칸으로", enabled = !added, onClick = onAdd)
            }
            if (m.place != null && (m.kind == ChatKind.DART || m.kind == ChatKind.PLACE)) CardButton("투표 후보로") { actions.openVote(listOf(m.place!!)) }
        }
    }
}

@Composable
private fun CardButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = Corner, border = BorderStroke(1.dp, Palette.Line)) {
        Text(label, fontSize = 16.sp, color = if (enabled) Palette.Navy else Palette.TextSoft, fontWeight = FontWeight.SemiBold)
    }
}
