package com.htb.carplan.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.htb.carplan.phone.calendar.CalendarScreen
import com.htb.carplan.phone.car.CarTab
import com.htb.carplan.phone.group.GroupTab
import com.htb.carplan.phone.group.GroupViewModel
import com.htb.carplan.phone.group.JoinDialog
import com.htb.carplan.phone.group.InviteInbox
import com.htb.carplan.phone.trip.TripDayScreen
import com.htb.carplan.phone.trip.TripRef
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htb.carplan.phone.record.RecordTab
import com.htb.carplan.phone.settings.SettingsTab
import com.htb.carplan.phone.login.LoginGate
import com.htb.carplan.phone.login.LoginViewModel

/** 하단 바 (2장): 캘린더 · 그룹 · 기록 · 차량 · 설정. */
enum class Tab(val label: String, val icon: ImageVector) {
    CALENDAR("캘린더", Icons.Filled.DateRange),
    GROUP("같이 탈 사람", Icons.Filled.Person),
    RECORD("기록", Icons.Filled.Edit),
    CAR("차량", Icons.Filled.Build),
    SETTINGS("설정", Icons.Filled.Settings),
}

@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableStateOf(Tab.CALENDAR) }
    var tapAgain by remember { mutableIntStateOf(0) }
    val loginVm: LoginViewModel = viewModel()
    val showLogin by loginVm.show.collectAsStateWithLifecycle()
    if (showLogin) {
        // 1장: 구글 · 카카오 · 나중에. An invite that brought the user here waits (JoinDialog shows right after).
        LoginGate(loginVm)
        return
    }
    // P-g: once, after the gate — name, how it works, the car's 6 digits.
    val context = androidx.compose.ui.platform.LocalContext.current
    var onboarding by rememberSaveable { mutableStateOf(Onboarding.needed(context)) }
    if (onboarding) {
        OnboardingScreen(onDone = { onboarding = false })
        return
    }
    // Q02: notifications are the one permission asked — once the app's own screen is up, not over the login gate and
    // the first-run guide (팀장 2026-11-11: 첫 화면 위에 시스템 창이 덮여서 보기 싫음).
    val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            kotlinx.coroutines.delay(800)
            askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val groupVm: GroupViewModel = viewModel()
    val pushTrip by InviteInbox.openTrip.collectAsStateWithLifecycle()
    // 손② (갤럭시 기본 앱): back on another tab goes to 캘린더 first; only 캘린더 leaves the app. Screens opened inside a
    // tab have their own back (BackScreen), registered later, so they still close first.
    androidx.activity.compose.BackHandler(enabled = tab != Tab.CALENDAR) { tab = Tab.CALENDAR }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        // Tabs slide sideways toward the one picked (the car's tab bar does the same).
        androidx.compose.animation.AnimatedContent(
            targetState = tab, modifier = Modifier.weight(1f).fillMaxWidth(), label = "tab",
            transitionSpec = {
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(MOTION_MS)) { dir * it / 8 } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(MOTION_MS))) togetherWith
                    (androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(MOTION_MS)) { -dir * it / 8 } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(MOTION_MS)))
            },
        ) { t ->
            Box(Modifier.fillMaxSize()) {
                androidx.compose.runtime.CompositionLocalProvider(LocalTabTapAgain provides tapAgain) {
                when (t) {
                    Tab.CALENDAR -> CalendarScreen()
                    Tab.GROUP -> GroupTab(groupVm)
                    Tab.RECORD -> RecordTab()
                    Tab.CAR -> CarTab()
                    Tab.SETTINGS -> SettingsTab()
                }
                }
                // "지웠어요 · 되살리기" after deleting an event or trip, over whatever tab is showing.
                com.htb.carplan.phone.calendar.DeletedBar(Modifier.align(Alignment.BottomCenter))
            }
        }
        TabBar(selected = tab, onSelect = { if (it == tab) tapAgain++ else tab = it })
    }
    // 17장: the push's trip day over the tabs, until the user goes back.
    pushTrip?.let { t -> BackScreen(onBack = { InviteInbox.clearTrip() }) { back -> TripDayScreen(TripRef(t[0], t[1], t[2]), onBack = back, parkingStopId = t.getOrNull(3)?.ifBlank { null }) } }
    }
    // An invite (link, QR, code) can arrive on any tab (1장 4): ask, then land in the group.
    JoinDialog(groupVm) { joined -> groupVm.landAfterJoin(joined) { tab = Tab.GROUP } }
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
                    Text(t.label, fontSize = 13.sp, color = tint, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}
