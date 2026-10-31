package com.htb.carplan.car

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htb.carplan.shared.data.InviteLinks

/**
 * 같이 탈 사람 탭 (④, 정원 2026-10-31: 차에서 초대는 QR로만, 채팅 같은 건 폰에). Left: one QR — today's trip if
 * there is one (shared on the first press), else the owner's friend QR. Right: who is on today's trip and the
 * owner's friends, names only.
 */
@Composable
fun RowScope.CarPeople(vm: CarHomeViewModel) {
    val c = LocalCarColors.current
    val trip by vm.trip.collectAsStateWithLifecycle()
    val group by vm.qrGroup.collectAsStateWithLifecycle()
    val members by vm.memberNames.collectAsStateWithLifecycle()
    val friends by vm.friendNames.collectAsStateWithLifecycle()
    val friendCode = vm.ownerFriendCode
    val compact = LocalCompact.current
    // Half screen: the QR on top, the names under it (one scroll), instead of side by side.
    if (compact) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { QrPart(vm, trip, group, friendCode, fill = false) }
            HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 20.dp))
            NamesPart(trip != null, members, friends)
        }
        return
    }
    Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 40.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        QrPart(vm, trip, group, friendCode, fill = true)
    }
    VerticalDivider(color = c.line)
    Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 40.dp, vertical = 24.dp).verticalScroll(rememberScrollState())) {
        NamesPart(trip != null, members, friends)
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.QrPart(vm: CarHomeViewModel, trip: TodayTrip?, group: com.htb.carplan.shared.model.Group?, friendCode: String?, fill: Boolean) {
    val c = LocalCarColors.current
    run {
        val g = group
        val t = trip
        when {
            g != null -> {
                Text("${t?.title ?: g.name}에 같이 가기", color = c.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Qr(InviteLinks.url(g.inviteCode))
                Text("폰으로 찍으면 바로 들어와요", color = c.ink, fontSize = 24.sp)
                Text("코드 ${g.inviteCode}", color = c.soft, fontSize = 20.sp, modifier = Modifier.padding(top = 4.dp))
            }
            t != null -> {
                // The trip is still the owner's alone: the first press gives it its own group (A안), then the QR shows.
                Text("${t.title}에 같이 갈 사람", color = c.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                if (fill) Spacer(Modifier.weight(1f))
                Text("누르면 QR이 떠요. 들어온 사람은 칸을 같이 보고 고칠 수 있어요.", color = c.soft, fontSize = 24.sp)
                Button(
                    onClick = vm::inviteToTodayTrip, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 96.dp), shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.onInk),
                ) { Text("이 여행 QR 띄우기", fontSize = 28.sp, fontWeight = FontWeight.Bold) }
                if (fill) Spacer(Modifier.weight(1f))
            }
            friendCode != null -> {
                Text("${vm.ownerName}님과 친구 맺기", color = c.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Qr(InviteLinks.url(friendCode))
                Text("오늘 여행이 없어서 친구 QR이에요", color = c.ink, fontSize = 24.sp)
                Text("친구가 되면 다음 일정부터 폰에서 이름만 눌러 초대해요", color = c.soft, fontSize = 20.sp, modifier = Modifier.padding(top = 4.dp))
            }
            else -> Text("QR을 준비하고 있어요", color = c.soft, fontSize = 26.sp)
        }
    }
}

@Composable
private fun NamesPart(hasTrip: Boolean, members: List<String>, friends: List<String>) {
    val c = LocalCarColors.current
    run {
        if (hasTrip) {
            Names("오늘 같이 가는 사람", members, empty = if (LocalCompact.current) "아직 없어요 · 위 QR을 찍으면 들어와요" else "아직 없어요 · 왼쪽 QR을 찍으면 들어와요")
            HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 20.dp))
        }
        Names("친구", friends, empty = "아직 없어요 · 여행 없는 날 QR을 찍으면 친구가 돼요")
        Text("자주 타는 사람 묶음·초대·코멘트는 폰에서 해요", color = c.soft, fontSize = 20.sp, modifier = Modifier.padding(top = 24.dp))
    }
}

@Composable
private fun Qr(link: String) {
    val bitmap = remember(link) { qrBitmap(link, 640) }
    // White quiet zone even at night: phones read a dark-on-light code best.
    Image(bitmap.asImageBitmap(), contentDescription = "초대 QR", modifier = Modifier.padding(vertical = 16.dp).background(Color.White).padding(12.dp).size(320.dp))
}

@Composable
private fun Names(title: String, names: List<String>, empty: String) {
    val c = LocalCarColors.current
    Text("$title  ${if (names.isEmpty()) "" else "${names.size}명"}", color = c.soft, fontSize = 20.sp)
    if (names.isEmpty()) Text(empty, color = c.soft, fontSize = 24.sp, modifier = Modifier.padding(top = 8.dp))
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        names.forEach { n ->
            Text(n, color = c.ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 10.dp))
            HorizontalDivider(color = c.line)
        }
    }
    Spacer(Modifier.height(4.dp))
}
