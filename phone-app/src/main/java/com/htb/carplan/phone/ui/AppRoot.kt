package com.htb.carplan.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.htb.carplan.phone.calendar.CalendarScreen

/** 하단 바 (2장): 캘린더 · 그룹 · 기록 · 차량 · 설정. */
enum class Tab(val label: String, val icon: ImageVector) {
    CALENDAR("캘린더", Icons.Filled.DateRange),
    GROUP("그룹", Icons.Filled.Person),
    RECORD("기록", Icons.Filled.Edit),
    CAR("차량", Icons.Filled.Build),
    SETTINGS("설정", Icons.Filled.Settings),
}

@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableStateOf(Tab.CALENDAR) }
    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        when (tab) {
            Tab.CALENDAR -> CalendarScreen()
            else -> ComingSoon(tab)
        }
        FloatingTabBar(
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp),
        )
    }
}

@Composable
private fun FloatingTabBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(32.dp), shadowElevation = 8.dp, color = Palette.Background) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Tab.entries.forEach { t ->
                val active = t == selected
                IconButton(
                    onClick = { onSelect(t) },
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(if (active) Palette.InputBg else Palette.Background),
                ) {
                    Icon(t.icon, contentDescription = t.label, tint = if (active) Palette.TextStrong else Palette.TextSoft)
                }
            }
        }
    }
}

@Composable
private fun ComingSoon(tab: Tab) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${tab.label} — 준비 중", color = Palette.TextSoft)
    }
}
