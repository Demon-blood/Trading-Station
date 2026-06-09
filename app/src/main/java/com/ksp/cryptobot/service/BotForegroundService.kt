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
import com.ksp.cryptobot.status.BotStatusStore
import kotlinx.coroutines.*

class BotForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var controller: BotController
    private lateinit var settingsStore: AppSettingsStore
    private lateinit var statusStore: BotStatusStore

    override fun onCreate() {
        super.onCreate()
        controller = BotController(applicationContext)
        settingsStore = AppSettingsStore(applicationContext)
        statusStore = BotStatusStore(applicationContext)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopBot()
            ACTION_START_BACKGROUND_AUTO -> startBot(backgroundAuto = true)
            ACTION_START -> startBot(backgroundAuto = false)
            else -> startBot(backgroundAuto = true)
        }
        return START_STICKY
    }

    private fun startBot(backgroundAuto: Boolean = true) {
        if (controller.running) {
            val already = "Bot service is already running."
            statusStore.write(already, "WARN")
            updateNotification(already)
            return
        }
        val settings = settingsStore.load()
        val text = when (settings.mode) {
            BotMode.PAPER -> "Background auto bot running in PAPER mode"
            BotMode.LIVE_CONFIRM -> "Background bot scanning in LIVE_CONFIRM mode"
            BotMode.LIVE_AUTO -> "Background auto bot running in LIVE_AUTO mode"
        }
        statusStore.write("Background auto bot service starting. Provider=${settings.exchangeProvider}, mode=${settings.mode}, manual=${settings.manualExecutionMode}, backgroundAuto=$backgroundAuto")
        startForeground(NOTIFICATION_ID, notification(text))
        controller.start()
        scope.launch {
            while (controller.running) {
                val current = settingsStore.load()
                val cycleStart = System.currentTimeMillis()
                try {
                    val shouldExecute = current.mode == BotMode.PAPER || current.mode == BotMode.LIVE_AUTO
                    val isPaper = current.mode == BotMode.PAPER || current.exchangeProvider == com.ksp.cryptobot.core.ExchangeProvider.PAPER
                    statusStore.write("Service cycle started. provider=${current.exchangeProvider}, mode=${current.mode}, paper=$isPaper, execute=$shouldExecute, interval=${current.scanIntervalSeconds}s")
                    controller.scanOnce(current, execute = shouldExecute)
                    updateNotification(statusStore.latestText())
                } catch (error: Exception) {
                    statusStore.write("Service cycle failed: ${error.message}", "ERROR")
                    updateNotification("Bot error: ${error.message}")
                }
                val elapsed = System.currentTimeMillis() - cycleStart
                val delayMs = (current.scanIntervalSeconds.coerceAtLeast(15L) * 1000L - elapsed).coerceAtLeast(5_000L)
                statusStore.write("Next scan in ${delayMs / 1000L}s")
                updateNotification(statusStore.latestText())
                delay(delayMs)
            }
        }
    }

    private fun stopBot() {
        statusStore.write("Stop requested. Foreground service shutting down.", "WARN")
        controller.stop()
        scope.coroutineContext.cancelChildren()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Crypto TradeStation")
        .setContentText(text.take(90))
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setOngoing(true)
        .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channels = listOf(
            NotificationChannel(CHANNEL_ID, "Bot status", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CHANNEL_TRADES, "Trade executions", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_RISK, "Risk and blocked trades", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_LEARNING, "Learning and strategy changes", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CHANNEL_API, "Exchange/API status", NotificationManager.IMPORTANCE_DEFAULT)
        )
        manager.createNotificationChannels(channels)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.ksp.cryptobot.START"
        const val ACTION_START_BACKGROUND_AUTO = "com.ksp.cryptobot.START_BACKGROUND_AUTO"
        const val ACTION_STOP = "com.ksp.cryptobot.STOP"
        private const val CHANNEL_ID = "bot_status"
        private const val CHANNEL_TRADES = "trade_executions"
        private const val CHANNEL_RISK = "risk_blocked_trades"
        private const val CHANNEL_LEARNING = "learning_strategy_changes"
        private const val CHANNEL_API = "exchange_api_status"
        private const val NOTIFICATION_ID = 1001
    }
}
