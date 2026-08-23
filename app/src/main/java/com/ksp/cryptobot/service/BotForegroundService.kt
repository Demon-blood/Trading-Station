package com.ksp.cryptobot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ksp.cryptobot.MainActivity
import com.ksp.cryptobot.cloudshare.CloudShareSyncEngine
import com.ksp.cryptobot.core.BotController
import com.ksp.cryptobot.core.BotMode
import com.ksp.cryptobot.core.ExchangeProvider
import com.ksp.cryptobot.data.AppDatabase
import com.ksp.cryptobot.governance.ProductionIntelligenceServiceMonitor
import com.ksp.cryptobot.exchange.KrakenRealtimeMarketDataRegistry
import com.ksp.cryptobot.exchange.KrakenPrivateExecutionRegistry
import com.ksp.cryptobot.settings.AppSettingsStore
import com.ksp.cryptobot.status.BotStatusStore
import kotlinx.coroutines.*

class BotForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var controller: BotController
    private lateinit var settingsStore: AppSettingsStore
    private lateinit var statusStore: BotStatusStore
    private lateinit var cloudShareSync: CloudShareSyncEngine
    private lateinit var productionMonitor: ProductionIntelligenceServiceMonitor
    private lateinit var hostStore: RuntimeHostStateStore
    private lateinit var connectivity: RuntimeConnectivityMonitor

    @Volatile
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        controller = BotController(applicationContext)
        settingsStore = AppSettingsStore(applicationContext)
        statusStore = BotStatusStore(applicationContext)
        cloudShareSync = CloudShareSyncEngine(applicationContext)
        productionMonitor = ProductionIntelligenceServiceMonitor(AppDatabase.get(applicationContext).governanceDao())
        hostStore = RuntimeHostStateStore(applicationContext)
        connectivity = RuntimeConnectivityMonitor(applicationContext) { state ->
            hostStore.network(state.summary())
        }
        createChannel()
        connectivity.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                hostStore.requestStop("User/service stop requested")
                stopBot()
            }
            ACTION_START_BACKGROUND_AUTO -> {
                hostStore.requestContinuousRun("USER_BACKGROUND_AUTO", resumeAfterBoot = true)
                startBot(recoveryReason = "user-background-auto")
            }
            ACTION_START -> {
                hostStore.requestContinuousRun("USER_START", resumeAfterBoot = false)
                startBot(recoveryReason = "user-start")
            }
            ACTION_RECOVER -> {
                if (hostStore.snapshot().desiredRunning) {
                    startBot(recoveryReason = intent.getStringExtra(EXTRA_RECOVERY_REASON) ?: "android-recovery")
                } else {
                    stopSelf()
                }
            }
            else -> {
                // START_STICKY may recreate the process with a null intent.
                // Only resume when the durable user intent still says RUN.
                if (hostStore.snapshot().desiredRunning) {
                    startBot(recoveryReason = "sticky-process-restart")
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startBot(recoveryReason: String) {
        if (loopJob?.isActive == true || controller.running) {
            val already = "Trading host already active. recovery=$recoveryReason"
            statusStore.write(already, "WARN")
            updateNotification(already)
            return
        }

        promoteToForeground("Starting trading host…")
        hostStore.recovery("STARTING:$recoveryReason")

        loopJob = scope.launch {
            productionMonitor.onServiceStart()

            RuntimeHostHealthInspector.inspect(applicationContext).forEach {
                statusStore.write(
                    "Runtime host ${it.name}: ${it.detail}",
                    if (it.status == "WARN") "WARN" else "INFO"
                )
            }

            val startSettings = settingsStore.load()
            if (!awaitUsableNetwork("startup")) return@launch
            configureRealtimeMarketData(startSettings, connectivity.snapshot.usable)
            configurePrivateExecutionState(startSettings, connectivity.snapshot.usable)

            if (startSettings.mode == BotMode.LIVE_AUTO) {
                updateNotification("LIVE_AUTO startup verification…")
                val verification = controller.runSystemFeatureVerification(startSettings)
                val failures = verification.filter { it.startsWith("FAIL") }
                if (failures.isNotEmpty()) {
                    val reason = failures.take(3).joinToString(" | ")
                    hostStore.failure("LIVE_AUTO startup verification blocked: $reason")
                    statusStore.write("LIVE_AUTO start blocked by preflight: $reason", "ERROR")
                    updateNotification("LIVE_AUTO blocked by preflight")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }
            }

            if (!reconcileAfterRecovery(startSettings, "startup:$recoveryReason")) {
                updateNotification("Waiting for safe exchange reconciliation")
            }

            controller.start()

            var lastNetworkUsable = connectivity.snapshot.usable
            while (isActive && controller.running && hostStore.snapshot().desiredRunning) {
                hostStore.heartbeat()
                productionMonitor.heartbeat()

                val network = connectivity.refresh()
                KrakenRealtimeMarketDataRegistry.onNetworkAvailable(network.usable)
                KrakenPrivateExecutionRegistry.onNetworkAvailable(network.usable)
                if (!network.usable) {
                    lastNetworkUsable = false
                    hostStore.recovery("PAUSED_NETWORK")
                    val message = "Network not validated (${network.summary()}). New scans/orders paused."
                    statusStore.write(message, "WARN")
                    updateNotification(message)
                    delay(NETWORK_RETRY_MS)
                    continue
                }

                if (!lastNetworkUsable) {
                    hostStore.recovery("NETWORK_RECOVERED_RECONCILING")
                    statusStore.write("Validated network returned. Reconciling exchange/application state before resuming.", "WARN")
                    if (!reconcileAfterRecovery(settingsStore.load(), "network-recovery")) {
                        updateNotification("Network restored; reconciliation not safe yet")
                        delay(RECONCILIATION_RETRY_MS)
                        continue
                    }
                    lastNetworkUsable = true
                }

                val current = settingsStore.load()
                configureRealtimeMarketData(current, network.usable)
                configurePrivateExecutionState(current, network.usable)
                val cycleStart = System.currentTimeMillis()
                try {
                    val cloud = cloudShareSync.syncIfDue()
                    if (cloud.error.isNotBlank()) {
                        statusStore.write("CloudShare sync deferred: ${cloud.error}", "WARN")
                    }

                    controller.processRemoteCommands(current)
                    if (!controller.running || !hostStore.snapshot().desiredRunning) break

                    val afterCommands = settingsStore.load()
                    val shouldExecute = afterCommands.mode == BotMode.PAPER || afterCommands.mode == BotMode.LIVE_AUTO

                    // For LIVE_AUTO, scanOnce performs its existing advanced
                    // order/lifecycle reconciliation before it can submit live work.
                    val decisions = controller.scanOnce(afterCommands, execute = shouldExecute)
                    hostStore.successfulCycle()

                    val hasTradableSignal = decisions.any {
                        it.allowedToTrade &&
                            (it.finalAction.name.contains("BUY") || it.finalAction.name.contains("SELL"))
                    }
                    val selectedDelay = when {
                        !afterCommands.dynamicScanIntervalEnabled -> afterCommands.scanIntervalSeconds
                        hasTradableSignal -> afterCommands.dynamicScanFastSeconds
                        else -> afterCommands.scanIntervalSeconds
                    }.coerceAtLeast(15L)

                    val modeText = "${afterCommands.mode}/${afterCommands.exchangeProvider}"
                    val wsHealth = KrakenRealtimeMarketDataRegistry.health()
                    val execHealth = KrakenPrivateExecutionRegistry.health()
                    updateNotification(
                        "RUNNING $modeText • net=${network.transports} • ws=${wsHealth.state}/${wsHealth.systemStatus} • exec=${execHealth.state}${if (execHealth.knownForEntries) "/known" else "/unknown"} • next=${selectedDelay}s • signals=${decisions.size}"
                    )
                } catch (error: Exception) {
                    productionMonitor.recordLoopError(error.message ?: error.javaClass.simpleName)
                    val failures = hostStore.failure(error.message ?: error.javaClass.simpleName)
                    hostStore.recovery("CYCLE_ERROR_$failures")
                    statusStore.write("Service cycle failed (#$failures): ${error.message}", "ERROR")
                    updateNotification("Trading host degraded • failures=$failures")
                    if (failures >= FAILURE_RECONCILE_THRESHOLD) {
                        reconcileAfterRecovery(settingsStore.load(), "cycle-failure-$failures")
                    }
                }

                val elapsed = System.currentTimeMillis() - cycleStart
                val latest = settingsStore.load()
                val recent = statusStore.recentLines(20)
                val fast = latest.dynamicScanIntervalEnabled &&
                    recent.any { it.contains("TradableSignal=true", ignoreCase = true) }
                val seconds = when {
                    !latest.dynamicScanIntervalEnabled -> latest.scanIntervalSeconds
                    fast -> latest.dynamicScanFastSeconds
                    else -> latest.scanIntervalSeconds
                }.coerceAtLeast(15L)
                delay((seconds * 1000L - elapsed).coerceAtLeast(5_000L))
            }

            if (!hostStore.snapshot().desiredRunning) {
                controller.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun configureRealtimeMarketData(
        settings: com.ksp.cryptobot.core.BotSettings,
        networkUsable: Boolean
    ) {
        val shouldRun = settings.enableKrakenWebSocketFeed &&
            settings.exchangeProvider == ExchangeProvider.KRAKEN
        if (!shouldRun) {
            KrakenRealtimeMarketDataRegistry.stop()
            return
        }
        KrakenRealtimeMarketDataRegistry.start()
        KrakenRealtimeMarketDataRegistry.onNetworkAvailable(networkUsable)
        KrakenRealtimeMarketDataRegistry.setActiveSymbols(settings.symbols())
        val health = KrakenRealtimeMarketDataRegistry.health()
        statusStore.write(
            "Kraken WS v2 market-data host: ${health.summary()}",
            if (health.lastError.isBlank()) "INFO" else "WARN"
        )
    }

    private fun configurePrivateExecutionState(
        settings: com.ksp.cryptobot.core.BotSettings,
        networkUsable: Boolean
    ) {
        val shouldRun = settings.exchangeProvider == ExchangeProvider.KRAKEN &&
            settings.mode != BotMode.PAPER
        if (!shouldRun) {
            KrakenPrivateExecutionRegistry.stop()
            return
        }

        val key = settingsStore.exchangeApiKey(ExchangeProvider.KRAKEN).orEmpty()
        val secret = settingsStore.exchangeSecretKey(ExchangeProvider.KRAKEN).orEmpty()
        if (key.isBlank() || secret.isBlank()) {
            KrakenPrivateExecutionRegistry.stop()
            return
        }

        KrakenPrivateExecutionRegistry.start(key, secret)
        KrakenPrivateExecutionRegistry.onNetworkAvailable(networkUsable)
        val health = KrakenPrivateExecutionRegistry.health()
        statusStore.write(
            "Kraken private execution-state host: ${health.summary()}",
            if (health.lastError.isBlank()) "INFO" else "WARN"
        )
    }

    private suspend fun awaitUsableNetwork(reason: String): Boolean {
        while (scope.isActive && hostStore.snapshot().desiredRunning) {
            val network = connectivity.refresh()
            if (network.usable) {
                hostStore.recovery("NETWORK_READY:$reason")
                return true
            }
            hostStore.recovery("WAITING_NETWORK:$reason")
            statusStore.write("Waiting for validated network before $reason. ${network.summary()}", "WARN")
            updateNotification("Waiting for validated network")
            delay(NETWORK_RETRY_MS)
        }
        return false
    }

    private suspend fun reconcileAfterRecovery(
        settings: com.ksp.cryptobot.core.BotSettings,
        reason: String
    ): Boolean {
        if (settings.mode == BotMode.PAPER || settings.exchangeProvider == ExchangeProvider.PAPER) {
            controller.loadLifecycleSnapshot(settings)
            hostStore.reconciliationSucceeded("paper:$reason")
            statusStore.write("Paper runtime state refreshed after $reason.", "INFO")
            return true
        }

        hostStore.recovery("RECONCILING:$reason")
        return runCatching {
            val health = controller.runKrakenDataHealth(settings)
            val hardFailures = health.filter { it.startsWith("FAIL") }
            require(hardFailures.isEmpty()) {
                "Kraken health failed: ${hardFailures.take(3).joinToString(" | ")}"
            }

            val openOrders = controller.loadOpenOrdersSnapshot(settings)
            val lifecycle = controller.loadLifecycleSnapshot(settings)
            val portfolio = controller.loadPortfolioSnapshot(settings)
            if (settings.exchangeProvider == ExchangeProvider.KRAKEN) {
                KrakenPrivateExecutionRegistry.markRestReconciled(openOrders.size)
            }

            hostStore.reconciliationSucceeded(
                "live:$reason orders=${openOrders.size} positions=${lifecycle.positions.size} assets=${portfolio.assets.size}"
            )
            statusStore.write(
                "Runtime reconciliation passed after $reason: openOrders=${openOrders.size}, lifecyclePositions=${lifecycle.positions.size}, portfolioAssets=${portfolio.assets.size}. Next LIVE_AUTO scan will run existing advanced reconciliation before execution.",
                "INFO"
            )
            true
        }.getOrElse { error ->
            val failures = hostStore.failure("Reconciliation failed after $reason: ${error.message}")
            hostStore.recovery("RECONCILIATION_FAILED:$reason")
            statusStore.write(
                "Runtime reconciliation failed after $reason (#$failures): ${error.message}. New scans/orders remain paused until retry.",
                "ERROR"
            )
            false
        }
    }

    private fun stopBot() {
        statusStore.write("Stop requested. Trading host shutting down.", "WARN")
        KrakenRealtimeMarketDataRegistry.stop()
        KrakenPrivateExecutionRegistry.stop()
        controller.stop()
        loopJob?.cancel()
        loopJob = null
        scope.coroutineContext.cancelChildren()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promoteToForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(text),
            type
        )
    }

    private fun notification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BotForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Crypto TradeStation")
            .setContentText(text.take(90))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_ID, "Bot status", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_TRADES, "Trade executions", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_RISK, "Risk and blocked trades", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_LEARNING, "Learning and strategy changes", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_API, "Exchange/API status", NotificationManager.IMPORTANCE_DEFAULT)
            )
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (hostStore.snapshot().desiredRunning) {
            hostStore.recovery("TASK_REMOVED_HOST_INTENT_PRESERVED")
            statusStore.write(
                "App UI task removed. Continuous host intent remains active; Android may keep or recreate the foreground service.",
                "INFO"
            )
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // specialUse is not one of Android 15's six-hour timed types, but keep
        // a defensive timeout implementation in case platform policy changes.
        hostStore.failure("Android foreground-service timeout type=$fgsType")
        statusStore.write("Android foreground-service timeout received. Stopping safely.", "ERROR")
        controller.stop()
        stopSelf(startId)
    }

    override fun onDestroy() {
        KrakenRealtimeMarketDataRegistry.stop()
        KrakenPrivateExecutionRegistry.stop()
        connectivity.stop()
        loopJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.ksp.cryptobot.START"
        const val ACTION_START_BACKGROUND_AUTO = "com.ksp.cryptobot.START_BACKGROUND_AUTO"
        const val ACTION_RECOVER = "com.ksp.cryptobot.RECOVER"
        const val ACTION_STOP = "com.ksp.cryptobot.STOP"
        const val EXTRA_RECOVERY_REASON = "recovery_reason"

        private const val CHANNEL_ID = "bot_status"
        private const val CHANNEL_TRADES = "trade_executions"
        private const val CHANNEL_RISK = "risk_blocked_trades"
        private const val CHANNEL_LEARNING = "learning_strategy_changes"
        private const val CHANNEL_API = "exchange_api_status"
        private const val NOTIFICATION_ID = 1001

        private const val NETWORK_RETRY_MS = 5_000L
        private const val RECONCILIATION_RETRY_MS = 15_000L
        private const val FAILURE_RECONCILE_THRESHOLD = 3
    }
}
