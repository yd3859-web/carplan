package com.htb.carplan.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.htb.carplan.phone.calendar.CalendarScreen
import com.htb.carplan.phone.car.CarTab

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
    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                Tab.CALENDAR -> CalendarScreen()
                Tab.CAR -> CarTab()
                else -> ComingSoon(tab)
            }
        }
        TabBar(selected = tab, onSelect = { tab = it })
    }
}

/** Flat system-style bar: hairline on top, icon + label, selected in black. */
@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Palette.Background).navigationBarsPadding()) {
        HorizontalDivider(color = Palette.Line)
        Row(Modifier.fillMaxWidth().height(60.dp)) {
            Tab.entries.forEach { t ->
                val active = t == selected
                val tint = if (active) Palette.TextStrong else Palette.TextSoft
                Column(
                    Modifier.weight(1f).fillMaxSize().clickable { onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(t.icon, contentDescription = t.label, tint = tint, modifier = Modifier.size(22.dp))
                    Text(t.label, fontSize = 11.sp, color = tint, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

@Composable
private fun ComingSoon(tab: Tab) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${tab.label} · 준비 중", color = Palette.TextSoft)
    }
}
