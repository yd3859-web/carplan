package com.htb.carplan.car

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htb.carplan.shared.model.Stop

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { TripSyncService.start(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val stops by TripSyncService.todayStops.collectAsStateWithLifecycle()
                    TodaySchedule(stops)
                }
            }
        }
    }
}

/** 8장 첫 화면의 왼쪽 "오늘 일정표" — 뼈대 단계에선 목록만. */
@Composable
private fun TodaySchedule(stops: List<Stop>) {
    Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("오늘 일정", fontSize = 32.sp)
        if (stops.isEmpty()) {
            Text("아직 일정이 없어요. 폰에서 장소를 추가해 보세요.", fontSize = 22.sp)
        }
        stops.forEachIndexed { i, stop ->
            Text("${i + 1}. ${stop.place.name}" + (stop.time?.let { "  ·  $it" } ?: ""), fontSize = 26.sp)
        }
    }
}
