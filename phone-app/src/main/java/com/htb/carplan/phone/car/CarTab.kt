package com.htb.carplan.phone.car

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.shared.data.CalendarRepository
import com.htb.carplan.shared.data.CarRepository
import com.htb.carplan.shared.data.SessionRepository
import com.htb.carplan.shared.model.Car
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

/** 7장 차 연결 (phone side): type the code on the car screen to become its owner/host (Q17). */
@OptIn(ExperimentalCoroutinesApi::class)
class CarTabViewModel : ViewModel() {
    private val calendar = CalendarRepository()
    private val cars = CarRepository()
    private val uid = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)

    val car: StateFlow<Car?> = uid.filterNotNull()
        .flatMapLatest(calendar::observeUser)
        .flatMapLatest { u -> u?.carId?.let(calendar::observeCar) ?: flowOf(null) }
        .catch { message.value = it.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { runCatching { SessionRepository().ensureSession() }.onSuccess { uid.value = it.uid } }
    }

    fun link(code: String) {
        val me = uid.value ?: return
        viewModelScope.launch {
            runCatching { cars.linkByCode(code, me) }
                .onSuccess { message.value = if (it == null) "그 코드의 차를 찾지 못했어요" else "차와 연결됐어요. 이제 이 차의 호스트예요" }
                .onFailure { message.value = it.message }
        }
    }
}

@Composable
fun CarTab(vm: CarTabViewModel = viewModel()) {
    val car by vm.car.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("차량", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        if (car == null) {
            Text("차 화면에 뜬 6자리 코드를 입력하면 이 차의 호스트가 돼요.", color = Palette.TextSoft)
            OutlinedTextField(
                code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("차 연결 코드") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { vm.link(code) }, enabled = code.length == 6, modifier = Modifier.fillMaxWidth()) { Text("연결") }
        } else {
            val s = car!!.state
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Panel).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("🚗 내 차 (호스트)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("연료 " + (s.fuelPercent?.let { "${it.toInt()}%" } ?: "—") + "  ·  주행가능 " + (s.rangeKm?.let { "${it.toInt()}km" } ?: "—"))
                Text("기어 ${s.gear.name}" + if (s.drivingRestricted) "  ·  주행 중" else "")
            }
            Text("차량 관리 기록은 15장에서 여기에 붙어요.", color = Palette.TextSoft, fontSize = 13.sp)
        }
        message?.let { Text(it, color = Palette.Teal) }
    }
}
