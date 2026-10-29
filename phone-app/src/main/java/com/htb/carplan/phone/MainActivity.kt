package com.htb.carplan.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.htb.carplan.phone.ui.AppRoot
import com.htb.carplan.phone.ui.CarplanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CarplanTheme { AppRoot() }
        }
    }
}
