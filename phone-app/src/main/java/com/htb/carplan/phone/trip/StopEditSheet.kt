package com.htb.carplan.phone.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.shared.calendar.RouteMath.hasCoords
import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.model.Stop

/**
 * One 장소 칸 (3장): place (required, via [PlaceSearchSheet]) + arrival time (optional, Q06) + memo.
 * [existing] null = new stop appended at the end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopEditSheet(
    vm: TripDayViewModel,
    existing: Stop?,
    onSave: (Place, String?, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var place by remember { mutableStateOf(existing?.place) }
    var time by remember { mutableStateOf(existing?.time) }
    var memo by remember { mutableStateOf(existing?.memo.orEmpty()) }
    var searchOpen by remember { mutableStateOf(existing == null) }
    var timePicker by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Background,
        shape = Corner,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).imePadding()) {
            Text(if (existing == null) "장소 칸 추가" else "장소 칸 고치기", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)

            Field(label = "장소", modifier = Modifier.padding(top = 14.dp)) {
                Column(Modifier.fillMaxWidth().clip(Corner).background(Palette.InputBg).clickable { searchOpen = true }.padding(12.dp)) {
                    Text(place?.name?.ifBlank { null } ?: "눌러서 장소 고르기", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (place == null) Palette.TextSoft else Palette.TextStrong)
                    place?.let { p ->
                        val sub = when {
                            p.address.isNotBlank() -> p.address
                            !p.hasCoords() -> "좌표 없음 · 카카오 검색으로 바꾸면 내비에 바로 들어가요"
                            else -> null
                        }
                        sub?.let { Text(it, fontSize = 12.sp, color = Palette.TextSoft) }
                    }
                }
            }

            Field(label = "도착 시각 (선택)", modifier = Modifier.padding(top = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { timePicker = true }) { Text(time ?: "시간 정하기", color = Palette.Navy, fontWeight = FontWeight.SemiBold) }
                    if (time != null) TextButton(onClick = { time = null }) { Text("지우기", color = Palette.TextSoft) }
                }
            }

            Field(label = "메모 (선택)", modifier = Modifier.padding(top = 4.dp)) {
                TextField(
                    memo, { memo = it }, placeholder = { Text("뭐 먹을지, 뭐 할지") }, modifier = Modifier.fillMaxWidth(),
                    shape = Corner,
                    colors = TextFieldDefaults.colors(focusedContainerColor = Palette.InputBg, unfocusedContainerColor = Palette.InputBg, focusedIndicatorColor = Palette.Navy, unfocusedIndicatorColor = Color.Transparent),
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { place?.let { onSave(it, time, memo.trim()) } },
                enabled = place != null && place!!.name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = Corner,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy),
            ) { Text(if (existing == null) "칸 추가" else "저장", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
    }

    if (searchOpen) {
        PlaceSearchSheet(
            vm = vm,
            initialQuery = place?.name.orEmpty(),
            onPick = { place = it; searchOpen = false },
            onDismiss = { searchOpen = false; if (place == null && existing == null) onDismiss() },
        )
    }

    if (timePicker) {
        val parts = time?.split(":")?.map { it.toInt() }
        val state = rememberTimePickerState(initialHour = parts?.get(0) ?: 10, initialMinute = parts?.get(1) ?: 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { timePicker = false },
            shape = Corner,
            containerColor = Palette.Background,
            title = { Text("도착 시각") },
            text = { TimeInput(state) },
            confirmButton = { TextButton(onClick = { time = "%02d:%02d".format(state.hour, state.minute); timePicker = false }) { Text("확인") } },
            dismissButton = { TextButton(onClick = { timePicker = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun Field(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = Palette.TextSoft, modifier = Modifier.padding(bottom = 4.dp))
        content()
    }
}
