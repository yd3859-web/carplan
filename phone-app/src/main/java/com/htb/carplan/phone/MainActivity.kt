package com.htb.carplan.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.htb.carplan.shared.model.Place

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    DevTripScreen()
                }
            }
        }
    }
}

// 뼈대 한 줄 연결 test places. Replaced by Kakao Local search in 3장.
private val samplePlaces = listOf(
    Place(name = "경복궁", lat = 37.5796, lng = 126.9770),
    Place(name = "서울숲", lat = 37.5444, lng = 127.0374),
    Place(name = "롯데월드타워", lat = 37.5125, lng = 127.1025),
)

@Composable
private fun DevTripScreen(vm: DevTripViewModel = viewModel()) {
    val stops by vm.stops.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    Column(
        Modifier.safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("오늘 일정 (테스트)", style = MaterialTheme.typography.headlineSmall)
        samplePlaces.forEach { place ->
            Button(onClick = { vm.addStop(place) }) { Text("+ ${place.name}") }
        }
        error?.let { Text("오류: $it", color = MaterialTheme.colorScheme.error) }
        stops.forEachIndexed { i, stop -> Text("${i + 1}. ${stop.place.name}") }
    }
}
