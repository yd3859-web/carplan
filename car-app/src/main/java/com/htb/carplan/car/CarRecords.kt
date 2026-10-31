package com.htb.carplan.car

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htb.carplan.shared.calendar.MaintenanceRules
import com.htb.carplan.shared.model.MaintenanceItem
import com.htb.carplan.shared.model.Record
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private val Corner = RoundedCornerShape(4.dp)

private fun dayText(d: LocalDate) = "${d.monthValue}월 ${d.dayOfMonth}일 (${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})"

/**
 * 기록 탭 (⑤, 정원 2026-10-31): the days with record slots, and one day's slots — when the car stopped, where,
 * how long, the short note, where it parked. Reading only: photos and notes are added on the phone.
 */
@Composable
fun RowScope.CarRecords(vm: CarHomeViewModel) {
    val c = LocalCarColors.current
    val days by vm.recordDays.collectAsStateWithLifecycle()
    val compact = LocalCompact.current
    var picked by remember { mutableStateOf<RecordDay?>(null) }
    // Wide: the first day is open from the start. Half screen: the list first, a day after a tap.
    val shown = picked ?: if (compact) null else days.firstOrNull()
    if (!compact || shown == null) {
        Column(Modifier.weight(if (compact) 1f else 0.8f).fillMaxHeight().padding(horizontal = if (compact) 20.dp else 28.dp, vertical = 20.dp)) {
            Text("기록", color = c.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text("사진·한 줄은 폰 기록 탭에서 남겨요", color = c.soft, fontSize = 20.sp, modifier = Modifier.padding(bottom = 8.dp))
            if (days.isEmpty()) Text("아직 기록이 없어요 · 차를 세우면 기록 칸이 생겨요", color = c.soft, fontSize = 24.sp, modifier = Modifier.padding(top = 12.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(days, key = { "${it.eventId}/${it.date}" }) { d ->
                    val on = d == shown
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 88.dp).background(if (on) c.sheet else c.bg, Corner).clickable { picked = d }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(dayText(d.date), color = c.soft, fontSize = 20.sp)
                            Text(d.title, color = c.ink, fontSize = 26.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1)
                        }
                        if (compact) Text("›", color = c.soft, fontSize = 28.sp)
                    }
                    HorizontalDivider(color = c.line)
                }
            }
        }
    }
    if (!compact) VerticalDivider(color = c.line)
    if (shown != null) Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = if (compact) 20.dp else 28.dp, vertical = 20.dp)) {
        if (compact) CarBackButton(onBack = { picked = null }, label = "목록")
        Text(shown.title, color = c.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(dayText(shown.date), color = c.soft, fontSize = 20.sp)
        val recs by remember(shown) { vm.recordsOf(shown) }.collectAsState(initial = null)
        Column(Modifier.weight(1f).padding(top = 12.dp).verticalScroll(rememberScrollState())) {
            when {
                recs == null -> Text("불러오는 중", color = c.soft, fontSize = 24.sp)
                recs!!.isEmpty() -> Text("이 날은 기록 칸이 없어요", color = c.soft, fontSize = 24.sp)
                else -> recs!!.forEach { RecordRow(it) }
            }
        }
    }
}

@Composable
private fun RecordRow(r: Record) {
    val c = LocalCarColors.current
    val hm = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }
    val arrived = r.arrivedAt?.toDate()
    val left = r.leftAt?.toDate()
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        Column(Modifier.width(116.dp)) {
            Text(arrived?.let(hm::format) ?: "—", color = c.ink, fontSize = 26.sp)
            if (arrived != null && left != null) Text("${((left.time - arrived.time) / 60_000).coerceAtLeast(1)}분 머묾", color = c.soft, fontSize = 20.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(r.place.name.ifBlank { "이름 없는 곳" }, color = c.ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            listOfNotNull(if (r.unplanned) "계획 밖" else null, r.photoUrls.size.takeIf { it > 0 }?.let { "사진 ${it}장" }, r.parkingNote.ifBlank { null }?.let { "주차 $it" })
                .joinToString(" · ").takeIf { it.isNotEmpty() }?.let { Text(it, color = c.soft, fontSize = 20.sp) }
            if (r.note.isNotBlank()) Text("“${r.note}”", color = c.ink, fontSize = 24.sp, maxLines = 3, modifier = Modifier.padding(top = 4.dp))
        }
    }
    HorizontalDivider(color = c.line)
}

/**
 * 차량 탭 (⑤): what the car says about itself (fuel, range, odometer, this month's fuel money) and each 차량 관리
 * item — when it is due, and [했어요] to log it today at the car's own odometer. Amounts and intervals: phone.
 */
@Composable
fun RowScope.CarVehicle(vm: CarHomeViewModel) {
    val c = LocalCarColors.current
    val compact = LocalCompact.current
    val v by vm.vehicle.collectAsStateWithLifecycle()
    val car by vm.car.collectAsStateWithLifecycle()
    val events by vm.careEvents.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val records = events.filter { !it.id.startsWith("due-") }
    val dues = events.filter { it.id.startsWith("due-") }.associateBy { it.maintenance?.item }
    val monthFuel = records.filter { it.maintenance?.item == MaintenanceItem.FUEL && it.startDate.startsWith(today.toString().take(7)) }.sumOf { it.maintenance?.amountWon ?: 0L }
    Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = if (compact) 20.dp else 40.dp, vertical = 20.dp)) {
        val stats = listOf(
            (if (v.isEv == true) "배터리" else "연료") to (v.fuelPercent?.let { "${it.toInt()}%" } ?: "—"),
            "주행가능" to (v.rangeKm?.takeIf { it > 0 }?.let { "${it.toInt()}km" } ?: "—"),
            "주행거리" to (v.odometerKm?.let { "%,dkm".format(it.toLong()) } ?: "—"),
            "이번 달 주유비" to "%,d원".format(monthFuel),
        )
        // Wide: four in a row. Half screen: two rows of two.
        stats.chunked(if (compact) 2 else 4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, color = c.soft, fontSize = 20.sp)
                        Text(value, color = c.ink, fontSize = 36.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
        HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("차량 관리", color = c.ink, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("금액·주기는 폰 차량 탭에서", color = c.soft, fontSize = 20.sp)
        }
        LazyColumn(Modifier.weight(1f)) {
            items(MaintenanceItem.entries.filter { it != MaintenanceItem.INSURANCE || dues[it] != null }, key = { it.name }) { item ->
                val last = records.firstOrNull { it.maintenance?.item == item }
                val due = dues[item]
                val dueDays = due?.let { ChronoUnit.DAYS.between(today, LocalDate.parse(it.startDate)) }
                Row(Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.label, color = c.ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                        Text(last?.let { "마지막 ${it.startDate.substring(5).replace('-', '/')}" + (it.maintenance?.odometerKm?.let { km -> " · ${"%,d".format(km.toLong())}km" } ?: "") } ?: "기록 없음", color = c.soft, fontSize = 20.sp)
                    }
                    // How soon, in a word: only overdue / today in the warning colour (one accent rule).
                    val (soon, warn) = when {
                        dueDays == null -> MaintenanceRules.interval(item, car?.intervals.orEmpty()).let { i -> listOfNotNull(i.km?.let { "${"%,d".format(it)}km" }, i.days?.let { "${it}일" }).joinToString("·").let { if (it.isEmpty()) "—" else "주기 $it" } } to false
                        dueDays < 0 -> "${-dueDays}일 지남" to true
                        dueDays == 0L -> "오늘" to true
                        else -> "${dueDays}일 남음" to false
                    }
                    Text(soon, color = if (warn) c.warn else c.ink, fontSize = 24.sp, fontWeight = if (warn) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(horizontal = 16.dp))
                    if (item != MaintenanceItem.INSURANCE) OutlinedButton(
                        onClick = { vm.logCare(item) }, shape = Corner, border = BorderStroke(1.dp, c.line),
                        modifier = Modifier.heightIn(min = 76.dp).width(if (compact) 120.dp else 140.dp),
                    ) { Text("했어요", color = c.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                }
                HorizontalDivider(color = c.line)
            }
        }
    }
}
