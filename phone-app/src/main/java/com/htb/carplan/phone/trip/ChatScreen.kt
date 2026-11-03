package com.htb.carplan.phone.trip

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
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

/**
 * 여행별 채팅방 (정원 2026-11-03 A안). Words plus the app's cards: 다트 결과 ([칸으로]), 투표 열림 ([투표하기]),
 * 투표 마감 (이긴 곳 [칸으로]). Full screen over the trip; the car never shows it.
 */
@Composable
fun ChatScreen(
    groupId: String, eventId: String?, title: String, me: String?, nameOf: (String?) -> String,
    onAddStop: ((Place) -> Unit)?, onOpenVote: () -> Unit, onDismiss: () -> Unit,
) {
    val repo = remember { ChatRepository() }
    val lines by remember(groupId) { repo.observe(groupId) }.collectAsStateWithLifecycle(emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var added by remember { mutableStateOf(setOf<String>()) }
    val list = rememberLazyListState()
    // Newest at the bottom; follow it as lines arrive, and count them as read.
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.animateScrollToItem(lines.lastIndex); ChatSeen.mark(context, groupId) }
    fun send() {
        val t = text.trim(); if (t.isEmpty() || me == null) return
        text = ""
        scope.launch { runCatching { repo.send(groupId, me, t, eventId) } }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        com.htb.carplan.phone.ui.Enter(com.htb.carplan.phone.ui.Entry.SCREEN) {
            Column(Modifier.fillMaxSize().background(Palette.Background).systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = Palette.TextStrong) }
                    Column(Modifier.weight(1f)) {
                        Text("채팅", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
                        Text(title, fontSize = 15.sp, color = Palette.TextSoft, maxLines = 1)
                    }
                }
                HorizontalDivider(color = Palette.Line)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (lines.isEmpty()) item {
                        Text("아직 아무 말도 없어요. 다트 결과나 투표도 여기로 올라와요", fontSize = 16.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 24.dp))
                    }
                    items(lines, key = { it.id }) { m ->
                        if (m.kind == ChatKind.TEXT) TextLine(m, mine = m.uid == me, nameOf = nameOf)
                        else CardLine(
                            m, nameOf = { if (it == me) "나" else nameOf(it) }, mine = m.uid == me, added = m.id in added,
                            onAdd = if (onAddStop != null && m.place != null) ({ onAddStop(m.place!!); added = added + m.id }) else null,
                            onOpenVote = onOpenVote,
                        )
                    }
                }
                HorizontalDivider(color = Palette.Line)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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

private fun hhmm(m: ChatMessage) = m.createdAt?.toDate()?.let { android.text.format.DateFormat.format("HH:mm", it).toString() } ?: ""

/** Mine: black, on the right. Others: white with a line, name above. */
@Composable
private fun TextLine(m: ChatMessage, mine: Boolean, nameOf: (String?) -> String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (!mine) Text(nameOf(m.uid), fontSize = 14.sp, color = Palette.TextSoft, modifier = Modifier.padding(bottom = 2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            if (mine) Text(hhmm(m), fontSize = 13.sp, color = Palette.TextSoft, modifier = Modifier.padding(end = 6.dp))
            Text(
                m.text, fontSize = 17.sp, color = if (mine) Color.White else Palette.TextStrong,
                modifier = Modifier.widthIn(max = 300.dp)
                    .then(if (mine) Modifier.background(Palette.Navy, Corner) else Modifier.border(1.dp, Palette.SheetEdge, Corner))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
            if (!mine) Text(hhmm(m), fontSize = 13.sp, color = Palette.TextSoft, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** A card the app posted: the whole width, a small label, the headline, and its one action. */
@Composable
private fun CardLine(m: ChatMessage, nameOf: (String?) -> String, mine: Boolean, added: Boolean, onAdd: (() -> Unit)?, onOpenVote: () -> Unit) {
    val label = when (m.kind) {
        ChatKind.DART -> if (mine) "다트 · 내가 올림" else "다트 · ${nameOf(m.uid)}님이 올림"
        ChatKind.VOTE_OPEN -> "투표가 열렸어요 · ${nameOf(m.uid)}"
        ChatKind.VOTE_CLOSED -> "투표 마감"
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
            ChatKind.VOTE_OPEN -> {
                Text(m.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.padding(top = 4.dp))
                m.lines.forEach { Text("· $it", fontSize = 16.sp, color = Palette.TextStrong) }
            }
            ChatKind.VOTE_CLOSED -> {
                Text(m.text, fontSize = 16.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 4.dp))
                Text(if (m.lines.isEmpty()) "결과 없음" else m.lines.joinToString(" · "), fontSize = 22.sp, fontWeight = FontWeight.Black, color = Palette.TextStrong)
            }
            ChatKind.TEXT -> {}
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (m.kind == ChatKind.VOTE_OPEN) OutlinedButton(onClick = onOpenVote, shape = Corner, border = BorderStroke(1.dp, Palette.Line)) {
                Text("투표하기", fontSize = 16.sp, color = Palette.Navy, fontWeight = FontWeight.SemiBold)
            }
            if (onAdd != null) OutlinedButton(onClick = onAdd, enabled = !added, shape = Corner, border = BorderStroke(1.dp, Palette.Line)) {
                Text(if (added) "칸으로 넣었어요" else "칸으로", fontSize = 16.sp, color = if (added) Palette.TextSoft else Palette.Navy, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
