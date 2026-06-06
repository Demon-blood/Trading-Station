package com.ksp.cryptobot.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Starter build does not auto-start after reboot for safety.
        // Add a persisted user opt-in setting before enabling this behavior.
    }
}
