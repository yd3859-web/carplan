package com.htb.carplan.phone

import android.content.Intent
import android.os.Bundle
import com.htb.carplan.phone.group.InviteInbox
import com.htb.carplan.phone.push.CarplanMessagingService
import com.htb.carplan.shared.data.SessionRepository
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.htb.carplan.phone.ui.AppRoot
import com.htb.carplan.phone.ui.CarplanTheme

class MainActivity : ComponentActivity() {
    private val notifications = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Q02: notifications are the one permission asked at first start.
        if (android.os.Build.VERSION.SDK_INT >= 33) notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        lifecycleScope.launch {
            runCatching { SessionRepository().ensureSession() }.getOrNull()?.let { CarplanMessagingService.syncToken(it.uid) }
        }
        InviteInbox.offer(intent?.dataString)
        InviteInbox.offerTrip(intent?.dataString)
        com.htb.carplan.phone.push.TripLive.start(this)
        setContent {
            CarplanTheme { AppRoot() }
        }
    }

    /** singleTop: an invite link tapped while the app is open arrives here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        InviteInbox.offer(intent.dataString)
        InviteInbox.offerTrip(intent.dataString)
    }
}
