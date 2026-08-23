package com.ksp.cryptobot.service

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

data class RuntimeHostHealthCheck(
    val status: String,
    val name: String,
    val detail: String
)

object RuntimeHostHealthInspector {
    fun inspect(context: Context): List<RuntimeHostHealthCheck> {
        val app = context.applicationContext
        val out = mutableListOf<RuntimeHostHealthCheck>()

        val activity = app.getSystemService(ActivityManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val restricted = activity.isBackgroundRestricted
            out += RuntimeHostHealthCheck(
                if (restricted) "WARN" else "PASS",
                "Android background restriction",
                if (restricted) "Android reports this app as background-restricted; continuous hosting may be suspended."
                else "Android does not report this app as background-restricted."
            )
        }

        val power = app.getSystemService(PowerManager::class.java)
        val dozeExempt = power.isIgnoringBatteryOptimizations(app.packageName)
        out += RuntimeHostHealthCheck(
            if (dozeExempt) "PASS" else "WARN",
            "Doze battery optimization",
            if (dozeExempt) "App is exempt from battery optimization/Doze restrictions."
            else "App is not battery-optimization exempt. For a dedicated 24/7 host, grant the appropriate battery/background access in Android settings."
        )

        val notifications = app.getSystemService(NotificationManager::class.java)
        val notificationEnabled = notifications.areNotificationsEnabled()
        out += RuntimeHostHealthCheck(
            if (notificationEnabled) "PASS" else "WARN",
            "Foreground notification",
            if (notificationEnabled) "Notifications are enabled."
            else "Notifications are disabled; Android can still run some foreground work, but the required persistent status surface is impaired."
        )

        val host = RuntimeHostStateStore(app).snapshot()
        out += RuntimeHostHealthCheck(
            "PASS",
            "Runtime host intent",
            "desiredRunning=${host.desiredRunning}, resumeAfterBoot=${host.resumeAfterBoot}, network=${host.networkState}, recovery=${host.recoveryState}, failures=${host.consecutiveFailures}"
        )
        return out
    }
}
