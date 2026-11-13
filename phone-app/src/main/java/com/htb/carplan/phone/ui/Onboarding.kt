package com.htb.carplan.phone.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.htb.carplan.shared.data.CalendarRepository
import com.htb.carplan.shared.data.CarRepository
import com.htb.carplan.shared.data.SessionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** P-g 첫 실행: shown once, right after the login gate. */
object Onboarding {
    private fun prefs(context: Context) = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)
    fun needed(context: Context) = !prefs(context).getBoolean("done", false)
    fun done(context: Context) = prefs(context).edit().putBoolean("done", true).apply()
}

/**
 * One calm screen instead of swipe pages: my name (people I go with see it), how carplan works in three lines, and
 * the car link right here (the 6 digits on the car screen) — everything can be skipped and done later.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var carMessage by remember { mutableStateOf<String?>(null) }
    var linked by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching {
            val s = SessionRepository().ensureSession()
            val u = CalendarRepository().observeUser(s.uid).first()
            name = u?.name.orEmpty()
            linked = u?.carId != null
        }
    }

    Column(
        Modifier.fillMaxSize().background(Palette.Background).systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            Spacer(Modifier.height(40.dp))
            Text("PlanCard는 이렇게 써요", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
            Spacer(Modifier.height(20.dp))
            Step("1", "캘린더에 일정과 장소를 써요", "그날 차에 타면 차가 \"여기로 갈까요?\" 하고 그 순서대로 내비를 켜요")
            Step("2", "같이 갈 사람을 초대해요", "일정마다 [같이 갈 사람]. 친구는 한 번만 맺으면 다음부터 이름만 눌러요")
            Step("3", "차를 세우면 기록이 남아요", "도착한 곳마다 칸이 생겨요. 사진과 한 줄은 폰에서")

            HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(top = 8.dp))
            Text("내 이름", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            OutlinedTextField(name, { name = it.take(20) }, singleLine = true, shape = Corner, placeholder = { Text("같이 가는 사람과 차 화면에 보여요") }, modifier = Modifier.fillMaxWidth())

            Text("차 연결", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
            if (linked) Text("차와 연결돼 있어요", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextStrong)
            else {
                Text("내 차라면, 차 화면에 뜬 6자리를 넣어요. 동승자는 넘어가도 돼요", fontSize = 15.sp, color = Palette.TextSoft)
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        code, { code = it.filter(Char::isDigit).take(6) }, singleLine = true, shape = Corner, placeholder = { Text("6자리") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            busy = true
                            scope.launch {
                                val me = runCatching { SessionRepository().ensureSession().uid }.getOrNull()
                                val r = me?.let { runCatching { CarRepository().linkByCode(code, it) } }
                                carMessage = when {
                                    r == null || r.isFailure -> "연결하지 못했어요 · ${r?.exceptionOrNull()?.message.orEmpty()}"
                                    r.getOrNull() == null -> "그 코드의 차를 찾지 못했어요"
                                    else -> { linked = true; null }
                                }
                                busy = false
                            }
                        },
                        enabled = code.length == 6 && !busy, shape = Corner, modifier = Modifier.height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy),
                    ) { Text("연결", fontWeight = FontWeight.SemiBold) }
                }
                carMessage?.let { Text(it, fontSize = 15.sp, color = Palette.Sunday, modifier = Modifier.padding(top = 6.dp)) }
            }

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = {
                    scope.launch {
                        if (name.isNotBlank()) runCatching { val s = SessionRepository().ensureSession(); SessionRepository().updateName(s.uid, name.trim()) }
                        Onboarding.done(context)
                        onDone()
                    }
                },
                shape = Corner, modifier = Modifier.fillMaxWidth().height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy),
            ) { Text("시작하기", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
            TextButton(onClick = { Onboarding.done(context); onDone() }, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 24.dp)) {
                Text("나중에 할게요", color = Palette.TextSoft, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun Step(n: String, title: String, sub: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(n, fontSize = 20.sp, fontWeight = FontWeight.Black, color = Palette.TextStrong, modifier = Modifier.width(32.dp))
        Column {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
            Text(sub, fontSize = 16.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Car link from anywhere (the today header's "차 연결하기"): the same 6 digits as the 차량 tab. */
@Composable
fun CarLinkSheet(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    CenterSheet(onDismiss = onDismiss) {
        Column {
            Text("차 연결", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
            Text("차 화면에 뜬 6자리를 넣으면 이 차의 호스트가 돼요. 연료·주행가능거리가 오늘 줄에 보여요", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    code, { code = it.filter(Char::isDigit).take(6) }, singleLine = true, shape = Corner, placeholder = { Text("6자리") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            val me = runCatching { SessionRepository().ensureSession().uid }.getOrNull()
                            val r = me?.let { runCatching { CarRepository().linkByCode(code, it) } }
                            busy = false
                            when {
                                r == null || r.isFailure -> message = "연결하지 못했어요 · ${r?.exceptionOrNull()?.message.orEmpty()}"
                                r.getOrNull() == null -> message = "그 코드의 차를 찾지 못했어요"
                                else -> onDismiss()
                            }
                        }
                    },
                    enabled = code.length == 6 && !busy, shape = Corner, modifier = Modifier.height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy),
                ) { Text("연결", fontWeight = FontWeight.SemiBold) }
            }
            message?.let { Text(it, fontSize = 15.sp, color = Palette.Sunday, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}
