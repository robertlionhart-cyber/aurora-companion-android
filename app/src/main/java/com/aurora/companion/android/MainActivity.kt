package com.aurora.companion.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aurora.companion.android.ui.AuroraApp
import com.aurora.companion.android.ui.AuroraTheme

class MainActivity : ComponentActivity() {
    private val vm: AuroraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AuroraTheme {
                AuroraApp()
            }
        }
    }

    override fun onResume() { super.onResume(); ReminderBus.appVisible = true }
    override fun onPause() { ReminderBus.appVisible = false; super.onPause() }
    override fun onStop() { super.onStop(); if (!isChangingConfigurations) vm.onAppBackground() }
}
