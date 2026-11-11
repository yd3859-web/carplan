package com.htb.carplan.phone.trip

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.htb.carplan.phone.ui.CenterSheet
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.shared.data.InviteLinks

/**
 * [같이 갈 사람] (초대 = 일정, 팀장 2026-10-31): who is coming, friends one tap each, a 묶음 (자주 타는 사람) all at
 * once, and someone new by QR · link · friend code. The first invite gives the event its own group; the screen
 * follows it there (TripDayViewModel.movedTo).
 */
@Composable
fun InviteSheet(vm: TripDayViewModel, onDismiss: () -> Unit) {
    val group by vm.group.collectAsStateWithLifecycle()
    val friends by vm.friends.collectAsStateWithLifecycle()
    val circles by vm.circles.collectAsStateWithLifecycle()
    val event by vm.event.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    vm.names.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    var code by remember { mutableStateOf<String?>(null) }
    var friendCode by remember { mutableStateOf("") }
    // Solo group: only me, whatever it holds. Members are the people who see this event.
    val members = group?.takeUnless { it.solo }?.memberIds.orEmpty()
    val me = vm.uid.collectAsStateWithLifecycle().value

    CenterSheet(onDismiss = onDismiss) {
        Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
            Text("같이 갈 사람", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
            Text(
                if (members.size <= 1) "아직 나만 가요" else (listOfNotNull(me) + members.filter { it != me }).joinToString(" · ") { vm.nameOf(it) },
                fontSize = 17.sp, color = Palette.TextStrong, modifier = Modifier.padding(top = 4.dp),
            )

            Section("친구")
            val invitable = friends.filterNot { it in members }
            if (invitable.isEmpty()) Text(
                if (friends.isEmpty()) "아직 친구가 없어요. 아래 QR·링크로 처음 초대하면 친구가 돼요" else "친구가 모두 같이 가요",
                fontSize = 15.sp, color = Palette.TextSoft,
            )
            invitable.forEach { uid ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(vm.nameOf(uid), fontSize = 17.sp, color = Palette.TextStrong, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.invite(listOf(uid)) }, enabled = !busy) { Text("초대", color = Palette.Navy, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
                }
            }

            // A 묶음 with nobody but me has no one to invite.
            val useful = circles.filter { c -> c.memberIds.any { it != me } }
            if (useful.isNotEmpty()) {
                Section("자주 타는 사람")
                useful.forEach { c ->
                    val missing = c.memberIds.filterNot { it in members || it == me }
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextStrong)
                            Text(c.memberIds.filter { it != me }.joinToString(" · ") { vm.nameOf(it) }.ifBlank { "나만" }, fontSize = 14.sp, color = Palette.TextSoft)
                        }
                        if (missing.isEmpty()) Text("같이 가요", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(end = 12.dp))
                        else TextButton(onClick = { vm.invite(missing) }, enabled = !busy) { Text("모두 초대", color = Palette.Navy, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
                    }
                }
            }

            Section("처음 보는 사람")
            val c = code
            if (c == null) {
                OutlinedButton(
                    onClick = { vm.inviteCode { code = it } }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(50.dp), shape = Corner, border = BorderStroke(1.dp, Palette.Line),
                ) { Text("QR · 링크 만들기", color = Palette.Navy, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) }
            } else {
                val qr = remember(c) { qrBitmap(InviteLinks.url(c)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(qr.asImageBitmap(), "초대 QR", Modifier.size(140.dp))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("폰 카메라로 찍으면 바로 들어와요", fontSize = 15.sp, color = Palette.TextSoft)
                        Text("코드  $c", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.padding(top = 6.dp))
                        Button(
                            onClick = {
                                val title = event?.title?.ifBlank { null } ?: "일정"
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, "\"$title\"에 같이 가요 — ${InviteLinks.url(c)} (코드 $c)")
                                context.startActivity(Intent.createChooser(send, "초대 링크 보내기"))
                            },
                            modifier = Modifier.padding(top = 8.dp).height(46.dp), shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy),
                        ) { Text("링크 보내기", fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
                    }
                }
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    friendCode, { friendCode = it.uppercase().take(6) }, placeholder = { Text("친구 코드 6자리", fontSize = 16.sp) }, singleLine = true, shape = Corner,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { vm.inviteByFriendCode(friendCode); friendCode = "" }, enabled = friendCode.length >= 4 && !busy,
                    shape = Corner, modifier = Modifier.height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy),
                ) { Text("초대", fontWeight = FontWeight.SemiBold) }
            }
            error?.let { Text(it, fontSize = 15.sp, color = Palette.Sunday, modifier = Modifier.padding(top = 8.dp)) }
            if (busy) Text("초대하는 중…", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 8.dp))

            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("닫기", color = Palette.TextSoft, fontSize = 17.sp) }
            }
        }
    }
}

@Composable
private fun Section(text: String) {
    HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(top = 16.dp))
    Text(text, fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
}

private fun qrBitmap(text: String, px: Int = 420): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px)
    val pixels = IntArray(px * px) { i -> if (m[i % px, i / px]) 0xFF111111.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888)
}
