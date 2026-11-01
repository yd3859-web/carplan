package com.htb.carplan.phone.car

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.htb.carplan.phone.ui.CenterSheet
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.shared.calendar.MaintenanceRules
import com.htb.carplan.shared.data.CalendarRepository
import com.htb.carplan.shared.data.CarRepository
import com.htb.carplan.shared.data.MaintenanceRepository
import com.htb.carplan.shared.data.Session
import com.htb.carplan.shared.data.SessionRepository
import com.htb.carplan.shared.model.Car
import com.htb.carplan.shared.model.Event
import com.htb.carplan.shared.model.MaintenanceItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** 7장 차 연결 + 15장 차량 관리 (phone side). */
@OptIn(ExperimentalCoroutinesApi::class)
class CarTabViewModel : ViewModel() {
    private val calendar = CalendarRepository()
    private val cars = CarRepository()
    private val maintenance = MaintenanceRepository()
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session
    val message = MutableStateFlow<String?>(null)

    val car: StateFlow<Car?> = _session.filterNotNull()
        .flatMapLatest { calendar.observeUser(it.uid) }
        .flatMapLatest { u -> u?.carId?.let(calendar::observeCar) ?: flowOf(null) }
        .catch { message.value = it.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 차량 관리 events of my solo group: records (past) and dues (`due-*`). */
    val events: StateFlow<List<Event>> = _session.filterNotNull()
        .flatMapLatest { maintenance.observe(it.soloGroupId) }
        .catch { message.value = it.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { runCatching { SessionRepository().ensureSession() }.onSuccess { _session.value = it } }
    }

    fun link(code: String) {
        val me = _session.value?.uid ?: return
        viewModelScope.launch {
            runCatching { cars.linkByCode(code, me) }
                .onSuccess { message.value = if (it == null) "그 코드의 차를 찾지 못했어요" else "차와 연결됐어요. 이제 이 차의 호스트예요" }
                .onFailure { message.value = it.message }
        }
    }

    /** Hand the car to another phone: it shows its 6 digits again. Only the host can (18장 rule). */
    fun unlink() {
        val me = _session.value?.uid ?: return
        val c = car.value ?: return
        viewModelScope.launch {
            runCatching { cars.unlink(c.id, me) }
                .onSuccess { message.value = "차 연결을 끊었어요. 차 화면에 6자리가 다시 떠요" }
                .onFailure { message.value = it.message }
        }
    }

    fun log(item: MaintenanceItem, date: LocalDate, odometerKm: Double?, amountWon: Long?, liters: Double?, memo: String) {
        val s = _session.value ?: return
        val c = car.value
        viewModelScope.launch {
            runCatching { maintenance.log(s.soloGroupId, c?.id.orEmpty(), item, date, odometerKm, amountWon, liters, memo, s.uid, intervals = c?.intervals.orEmpty()) }
                .onFailure { message.value = it.message }
        }
    }

    fun setInsuranceDate(date: LocalDate) {
        val s = _session.value ?: return
        viewModelScope.launch { runCatching { maintenance.setDueDate(s.soloGroupId, car.value?.id.orEmpty(), MaintenanceItem.INSURANCE, date, s.uid) }.onFailure { message.value = it.message } }
    }

    fun setInterval(item: MaintenanceItem, km: Long?, days: Long?) {
        val c = car.value ?: run { message.value = "차를 먼저 연결해 주세요"; return }
        viewModelScope.launch { runCatching { cars.setInterval(c.id, item, km, days) }.onFailure { message.value = it.message } }
    }

    fun deleteRecord(eventId: String) {
        val s = _session.value ?: return
        viewModelScope.launch { runCatching { maintenance.delete(s.soloGroupId, eventId) }.onFailure { message.value = it.message } }
    }
}

@Composable
fun CarTab(vm: CarTabViewModel = viewModel()) {
    val car by vm.car.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    com.htb.carplan.phone.ui.OnTabTapAgain { list.animateScrollToItem(0) }
    var code by remember { mutableStateOf("") }
    var logging by remember { mutableStateOf<MaintenanceItem?>(null) }
    var intervalFor by remember { mutableStateOf<MaintenanceItem?>(null) }
    var showRecords by remember { mutableStateOf<MaintenanceItem?>(null) }
    val today = LocalDate.now()
    val records = events.filterNot { it.id.startsWith("due-") }
    val dues = events.filter { it.id.startsWith("due-") }.associateBy { it.maintenance?.item }
    val monthFuel = records.filter { it.maintenance?.item == MaintenanceItem.FUEL && it.startDate.startsWith(YearMonth.now().toString()) }.sumOf { it.maintenance?.amountWon ?: 0L }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        Text("차량", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
        message?.let { Text(it, color = Palette.Teal, fontSize = 15.sp, modifier = Modifier.clickable { vm.message.value = null }) }
        if (car == null) {
            Text("차 화면에 뜬 6자리 코드를 입력하면 이 차의 호스트가 돼요.", color = Palette.TextSoft, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
            OutlinedTextField(
                code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("차 연결 코드") }, singleLine = true, shape = Corner,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(onClick = { vm.link(code) }, enabled = code.length == 6, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(50.dp), shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy)) { Text("연결", fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(16.dp))
        } else {
            val s = car!!.state
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                BigStat(if (s.isEv == true) "배터리" else "연료", s.fuelPercent?.let { "${it.toInt()}" } ?: "—", "%")
                BigStat("주행가능", s.rangeKm?.takeIf { it > 0 }?.let { "${it.toInt()}" } ?: "—", "km")
                BigStat("주행거리", s.odometerKm?.let { "%,d".format(it.toLong()) } ?: "—", "km")
                BigStat("이번 달 주유비", "%,d".format(monthFuel), "원")
            }
            var confirmUnlink by remember { mutableStateOf(false) }
            if (car!!.ownerUid == vm.session.collectAsStateWithLifecycle().value?.uid) {
                androidx.compose.material3.TextButton(onClick = { confirmUnlink = true }, modifier = Modifier.padding(bottom = 6.dp)) { Text("이 차 연결 끊기", color = Palette.TextSoft, fontSize = 15.sp) }
            }
            if (confirmUnlink) com.htb.carplan.phone.ui.CenterSheet(onDismiss = { confirmUnlink = false }) {
                Column {
                    Text("차 연결을 끊을까요?", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
                    Text("차 화면에 6자리가 다시 떠요. 다른 폰(또는 이 폰)에서 그 코드를 넣으면 그 폰이 호스트가 돼요. 일정·기록은 그대로예요.", fontSize = 16.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                        androidx.compose.material3.TextButton(onClick = { confirmUnlink = false }) { Text("취소", color = Palette.TextSoft, fontSize = 17.sp) }
                        Button(onClick = { confirmUnlink = false; vm.unlink() }, shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy)) { Text("연결 끊기", fontSize = 17.sp) }
                    }
                }
            }
        }
        HorizontalDivider(color = Palette.Line)
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("차량 관리", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.weight(1f))
            Text("항목을 누르면 기록·주기", fontSize = 15.sp, color = Palette.TextSoft)
        }
        LazyColumn(Modifier.weight(1f), state = list) {
            items(MaintenanceItem.entries, key = { it.name }) { item ->
                val last = records.firstOrNull { it.maintenance?.item == item }
                val due = dues[item]
                val dueDays = due?.let { ChronoUnit.DAYS.between(today, LocalDate.parse(it.startDate)) }
                val interval = MaintenanceRules.interval(item, car?.intervals.orEmpty())
                Row(Modifier.fillMaxWidth().clickable { showRecords = item }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.label, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
                        Text(
                            last?.let { "마지막 ${it.startDate}" + (it.maintenance?.odometerKm?.let { km -> " · ${"%,d".format(km.toLong())}km" } ?: "") } ?: "기록 없음",
                            fontSize = 15.sp, color = Palette.TextSoft,
                        )
                        val dueText = when {
                            due == null && item == MaintenanceItem.INSURANCE -> "갱신일을 넣어 주세요"
                            due == null -> listOfNotNull(interval.km?.let { "${"%,d".format(it)}km" }, interval.days?.let { "${it}일" }).joinToString(" 또는 ").ifEmpty { "" }.let { if (it.isEmpty()) "" else "주기 $it" }
                            dueDays!! < 0 -> "예정 지남 (${-dueDays}일 전)"
                            dueDays == 0L -> "오늘 예정"
                            else -> "다음 ${due.startDate} · ${dueDays}일 남음" + (due.maintenance?.dueKm?.let { " · ${"%,d".format(it.toLong())}km" } ?: "")
                        }
                        if (dueText.isNotEmpty() && dueDays == null) Text(dueText, fontSize = 15.sp, color = Palette.TextSoft)
                    }
                    // Right side: how soon, in one word — the thing you actually want to know.
                    when {
                        dueDays == null -> Text(if (item == MaintenanceItem.INSURANCE) "갱신일 없음" else "—", fontSize = 16.sp, color = Palette.TextSoft)
                        dueDays < 0 -> Text("${-dueDays}일 지남", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Sunday)
                        dueDays == 0L -> Text("오늘", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Sunday)
                        else -> Column(horizontalAlignment = Alignment.End) {
                            Text("${dueDays}일 남음", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (dueDays <= 7) Palette.Sunday else Palette.Teal)
                            Text(due!!.startDate.substring(5).replace('-', '/') + (due.maintenance?.dueKm?.let { " · ${"%,d".format(it.toLong())}km" } ?: ""), fontSize = 15.sp, color = Palette.TextSoft)
                        }
                    }
                    Text("  ›", fontSize = 22.sp, color = Palette.TextSoft)
                }
                HorizontalDivider(color = Palette.Line)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    logging?.let { item ->
        if (item == MaintenanceItem.INSURANCE) DateDialog(title = "보험 갱신일", onPick = { vm.setInsuranceDate(it); logging = null }, onDismiss = { logging = null })
        else LogDialog(item, defaultKm = car?.state?.odometerKm, onSave = { d, km, won, l, memo -> vm.log(item, d, km, won, l, memo); logging = null }, onDismiss = { logging = null })
    }
    intervalFor?.let { item ->
        val cur = MaintenanceRules.interval(item, car?.intervals.orEmpty())
        IntervalDialog(item, cur.km, cur.days, onSave = { km, d -> vm.setInterval(item, km, d); intervalFor = null }, onDismiss = { intervalFor = null })
    }
    showRecords?.let { item ->
        val list = records.filter { it.maintenance?.item == item }
        CenterSheet(onDismiss = { showRecords = null }) {
            Column {
                Text(item.label, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
                val cur = MaintenanceRules.interval(item, car?.intervals.orEmpty())
                if (item != MaintenanceItem.FUEL && item != MaintenanceItem.INSURANCE) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("주기  " + (listOfNotNull(cur.km?.let { "${"%,d".format(it)}km" }, cur.days?.let { "${it}일" }).joinToString(" 또는 ").ifEmpty { "없음" }), fontSize = 16.sp, color = Palette.TextSoft, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showRecords = null; intervalFor = item }) { Text("주기 바꾸기", color = Palette.Navy, fontSize = 15.sp) }
                }
                Button(
                    onClick = { showRecords = null; logging = item }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp).height(52.dp), shape = Corner,
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy),
                ) { Text(if (item == MaintenanceItem.INSURANCE) "갱신일 넣기" else "${item.label} 했어요 · 기록", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                Text("기록", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 4.dp))
                if (list.isEmpty()) Text("아직 없어요", color = Palette.TextSoft, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp))
                list.take(20).forEach { e ->
                    val m = e.maintenance
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(e.startDate, fontSize = 16.sp, color = Palette.TextSoft, modifier = Modifier.width(100.dp))
                        Text(listOfNotNull(m?.odometerKm?.let { "${"%,d".format(it.toLong())}km" }, m?.amountWon?.let { "${"%,d".format(it)}원" }, m?.liters?.let { "${it}L" }, e.memo.takeIf { it.isNotBlank() }, if (m?.auto == true) "차가 기록" else null).joinToString(" · "), fontSize = 16.sp, color = Palette.TextStrong, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.deleteRecord(e.id) }) { Text("삭제", color = Palette.TextSoft, fontSize = 15.sp) }
                    }
                    HorizontalDivider(color = Palette.Line)
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) { TextButton(onClick = { showRecords = null }) { Text("닫기", color = Palette.Navy) } }
            }
        }
    }
}

@Composable
private fun LogDialog(item: MaintenanceItem, defaultKm: Double?, onSave: (LocalDate, Double?, Long?, Double?, String) -> Unit, onDismiss: () -> Unit) {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var km by remember { mutableStateOf(defaultKm?.toLong()?.toString() ?: "") }
    var won by remember { mutableStateOf("") }
    var liters by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    val dateOk = runCatching { LocalDate.parse(date) }.isSuccess
    CenterSheet(onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${item.label} 기록", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
            OutlinedTextField(date, { date = it }, label = { Text("날짜 (yyyy-MM-dd)") }, singleLine = true, shape = Corner, isError = !dateOk, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(km, { km = it.filter(Char::isDigit) }, label = { Text("주행거리 km (차에서 읽은 값)") }, singleLine = true, shape = Corner, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            if (item == MaintenanceItem.FUEL) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(won, { won = it.filter(Char::isDigit) }, label = { Text("금액 원 (선택)") }, singleLine = true, shape = Corner, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                OutlinedTextField(liters, { liters = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("리터 (선택)") }, singleLine = true, shape = Corner, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
            }
            OutlinedTextField(memo, { memo = it }, label = { Text("메모 (선택)") }, singleLine = true, shape = Corner, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("취소", color = Palette.TextSoft) }
                Button(enabled = dateOk, onClick = { onSave(LocalDate.parse(date), km.toDoubleOrNull(), won.toLongOrNull(), liters.toDoubleOrNull(), memo) }, shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy)) { Text("저장") }
            }
        }
    }
}

@Composable
private fun DateDialog(title: String, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    var date by remember { mutableStateOf(LocalDate.now().plusYears(1).toString()) }
    val ok = runCatching { LocalDate.parse(date) }.isSuccess
    CenterSheet(onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
            OutlinedTextField(date, { date = it }, label = { Text("yyyy-MM-dd") }, singleLine = true, shape = Corner, isError = !ok, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("취소", color = Palette.TextSoft) }
                Button(enabled = ok, onClick = { onPick(LocalDate.parse(date)) }, shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy)) { Text("저장") }
            }
        }
    }
}

@Composable
private fun IntervalDialog(item: MaintenanceItem, km0: Long?, days0: Long?, onSave: (Long?, Long?) -> Unit, onDismiss: () -> Unit) {
    var km by remember { mutableStateOf(km0?.toString() ?: "") }
    var days by remember { mutableStateOf(days0?.toString() ?: "") }
    CenterSheet(onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${item.label} 주기", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
            Text("둘 다 넣으면 먼저 오는 쪽에 예정이 잡혀요. 비우면 그 규칙은 안 써요.", fontSize = 15.sp, color = Palette.TextSoft)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(km, { km = it.filter(Char::isDigit) }, label = { Text("km마다") }, singleLine = true, shape = Corner, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                OutlinedTextField(days, { days = it.filter(Char::isDigit) }, label = { Text("일마다") }, singleLine = true, shape = Corner, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onSave(null, null) }) { Text("기본값으로", color = Palette.TextSoft) }
                TextButton(onClick = onDismiss) { Text("취소", color = Palette.TextSoft) }
                Button(onClick = { onSave(km.toLongOrNull(), days.toLongOrNull()) }, shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy)) { Text("저장") }
            }
        }
    }
}

@Composable
private fun BigStat(label: String, value: String, unit: String) {
    Column {
        Text(label, fontSize = 15.sp, color = Palette.TextSoft)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
            Text(" $unit", fontSize = 16.sp, color = Palette.TextSoft, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}
