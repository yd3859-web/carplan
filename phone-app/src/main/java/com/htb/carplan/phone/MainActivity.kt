package com.htb.carplan.phone

import android.content.Intent
import android.os.Bundle
import com.htb.carplan.phone.group.InviteInbox
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.htb.carplan.phone.ui.AppRoot
import com.htb.carplan.phone.ui.CarplanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        InviteInbox.offer(intent?.dataString)
        setContent {
            CarplanTheme { AppRoot() }
        }
    }

    /** singleTop: an invite link tapped while the app is open arrives here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        InviteInbox.offer(intent.dataString)
    }
}
