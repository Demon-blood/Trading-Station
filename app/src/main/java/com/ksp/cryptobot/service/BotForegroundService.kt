package com.ksp.cryptobot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ksp.cryptobot.core.BotController
import com.ksp.cryptobot.core.BotMode
import com.ksp.cryptobot.settings.AppSettingsStore
import kotlinx.coroutines.*

class BotForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var controller: BotController
    private lateinit var settingsStore: AppSettingsStore

    override fun onCreate() {
        super.onCreate()
        controller = BotController(applicationContext)
        settingsStore = AppSettingsStore(applicationContext)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopBot()
            else -> startBot()
        }
        return START_STICKY
    }

    private fun startBot() {
        val settings = settingsStore.load()
        val text = when (settings.mode) {
            BotMode.PAPER -> "Bot running in PAPER mode"
            BotMode.LIVE_CONFIRM -> "Bot scanning in LIVE_CONFIRM mode"
            BotMode.LIVE_AUTO -> "Bot running in LIVE_AUTO mode"
        }
        startForeground(NOTIFICATION_ID, notification(text))
        controller.start()
        scope.launch {
            while (controller.running) {
                val current = settingsStore.load()
                try {
                    controller.scanOnce(current, execute = current.mode != BotMode.PAPER)
                } catch (_: Exception) {
                    // Keep service alive; failures are recorded in scan decisions when possible.
                }
                delay((current.scanIntervalSeconds.coerceAtLeast(15L)) * 1000L)
            }
        }
    }

    private fun stopBot() {
        controller.stop()
        scope.coroutineContext.cancelChildren()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("KSP Crypto Bot")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setOngoing(true)
        .build()

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Bot status", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.ksp.cryptobot.START"
        const val ACTION_STOP = "com.ksp.cryptobot.STOP"
        private const val CHANNEL_ID = "bot_status"
        private const val NOTIFICATION_ID = 1001
    }
}
