#!/usr/bin/env python3
"""Wire Settings System navigation and full diagnostics export.

Run AFTER apply_exact_preview_ui.py.
"""
from __future__ import annotations

import sys
from pathlib import Path

CONTROLLER_METHOD = '\n    /**\n     * Runs the app-level verification suite and exports a privacy-safe runtime\n     * diagnostics report. Exchange/API secrets, bot tokens, webhook secrets and\n     * remote command PINs are explicitly redacted from every text section.\n     */\n    suspend fun exportFullDiagnosticsToFile(\n        settings: BotSettings = settingsStore.load(),\n        customDirectoryPath: String = settingsStore.diagnosticsDirectoryPath()\n    ): Pair<List<String>, String> {\n        val verification = runCatching { runSystemFeatureVerification(settings) }\n            .getOrElse { error ->\n                listOf("FAIL | Full Diagnostics | System verification aborted: ${error.message}")\n            }\n\n        return try {\n            val portfolio = runCatching { loadPortfolioSnapshot(settings) }.getOrNull()\n            val lifecycle = runCatching { loadLifecycleSnapshot(settings) }.getOrNull()\n            val openOrders = runCatching { loadOpenOrdersSnapshot(settings) }.getOrDefault(emptyList())\n            val trades = runCatching { loadTradeJournal(250) }.getOrDefault(emptyList())\n            val recentStatus = statusStore.recentLines(500)\n            val providerHealth = runCatching {\n                com.ksp.cryptobot.news.NewsProviderHealthRegistry.snapshot().map { it.toString() }\n            }.getOrDefault(emptyList())\n\n            val secretValues = listOfNotNull(\n                settingsStore.exchangeApiKey(ExchangeProvider.KRAKEN),\n                settingsStore.exchangeSecretKey(ExchangeProvider.KRAKEN),\n                settingsStore.telegramBotToken(),\n                settingsStore.discordWebhookUrl(),\n                settingsStore.discordBotToken(),\n                settingsStore.remoteCommandPin(),\n                settingsStore.newsApiKey(),\n                settingsStore.cryptoPanicApiKey(),\n                settingsStore.marketauxApiKey(),\n                settingsStore.newsDataApiKey(),\n                settingsStore.gNewsApiKey(),\n                settingsStore.guardianApiKey()\n            ).filter { it.length >= 4 }.distinct()\n\n            fun sanitize(raw: String): String {\n                return secretValues.fold(raw) { safe, secret ->\n                    safe.replace(secret, "[REDACTED]", ignoreCase = false)\n                }\n            }\n\n            val packageInfo = runCatching {\n                appContext.packageManager.getPackageInfo(appContext.packageName, 0)\n            }.getOrNull()\n\n            val report = buildString {\n                appendLine("CRYPTO TRADESTATION — FULL APP DIAGNOSTICS")\n                appendLine("generatedAt=${java.time.Instant.now()}")\n                appendLine("package=${appContext.packageName}")\n                appendLine("versionName=${packageInfo?.versionName ?: "unknown"}")\n                appendLine("androidSdk=${android.os.Build.VERSION.SDK_INT}")\n                appendLine("device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")\n                appendLine("mode=${settings.mode}")\n                appendLine("exchangeProvider=${settings.exchangeProvider}")\n                appendLine("symbols=${settings.symbolsCsv}")\n                appendLine("allowedQuoteAssets=${settings.allowedQuoteAssetsCsv}")\n                appendLine("liveTradingAcknowledged=${settings.liveTradingAcknowledged}")\n                appendLine("ultimateAutomationEnabled=${settings.ultimateAutomationEnabled}")\n                appendLine("liveLifecycleManagerEnabled=${settings.liveLifecycleManagerEnabled}")\n                appendLine("autoExitManagerEnabled=${settings.autoExitManagerEnabled}")\n                appendLine("autoStopLossEnabled=${settings.autoStopLossEnabled}")\n                appendLine("autoTakeProfitEnabled=${settings.autoTakeProfitEnabled}")\n                appendLine("trueSelfLearningEnabled=${settings.trueSelfLearningEnabled}")\n                appendLine("maxPositionEur=${settings.maxPositionEur}")\n                appendLine("maxDailyLossEur=${settings.maxDailyLossEur}")\n                appendLine("maxTradesPerDay=${settings.maxTradesPerDay}")\n                appendLine("maxTradesPerHour=${settings.maxTradesPerHour}")\n                appendLine("maxSimultaneousLivePositions=${settings.maxSimultaneousLivePositions}")\n                appendLine("secrets=EXCLUDED_AND_REDACTED")\n                appendLine()\n\n                appendLine("[SYSTEM_VERIFICATION]")\n                verification.forEach { appendLine(sanitize(it)) }\n                appendLine()\n\n                appendLine("[PORTFOLIO]")\n                if (portfolio == null) {\n                    appendLine("unavailable")\n                } else {\n                    appendLine("provider=${portfolio.provider}")\n                    appendLine("totalValueEur=${portfolio.totalValueEur}")\n                    appendLine("freeEur=${portfolio.freeEur}")\n                    if (portfolio.warning.isNotBlank()) appendLine("warning=${sanitize(portfolio.warning)}")\n                    portfolio.assets.take(80).forEach { asset ->\n                        appendLine("${asset.asset}|total=${asset.total}|free=${asset.free}|eurValue=${asset.eurValue}")\n                    }\n                }\n                appendLine()\n\n                appendLine("[LIFECYCLE_POSITIONS]")\n                if (lifecycle == null) {\n                    appendLine("unavailable")\n                } else {\n                    appendLine("positions=${lifecycle.positions.size}")\n                    lifecycle.positions.take(80).forEach { position ->\n                        appendLine(\n                            "${position.symbol}|qty=${position.quantity}|entry=${position.entryPrice}|" +\n                                "current=${position.currentPrice}|pnlEur=${position.unrealizedPnlEur}|" +\n                                "pnlPct=${position.unrealizedPnlPercent}|managed=${position.managed}"\n                        )\n                    }\n                    lifecycle.messages.take(80).forEach { appendLine("message=${sanitize(it)}") }\n                }\n                appendLine()\n\n                appendLine("[OPEN_ORDERS]")\n                appendLine("count=${openOrders.size}")\n                openOrders.take(100).forEach { order ->\n                    appendLine(\n                        "${order.side}|${order.symbol}|${order.orderType}|price=${order.price}|" +\n                            "quantity=${order.quantity}|executed=${order.executedQuantity}|" +\n                            "remaining=${order.remainingQuantity}|status=${order.status}"\n                    )\n                }\n                appendLine()\n\n                appendLine("[NEWS_PROVIDER_HEALTH]")\n                if (providerHealth.isEmpty()) appendLine("no provider health snapshot")\n                else providerHealth.take(100).forEach { appendLine(sanitize(it)) }\n                appendLine()\n\n                appendLine("[RECENT_TRADES]")\n                appendLine("count=${trades.size}")\n                trades.take(250).forEach { trade ->\n                    appendLine(\n                        "${trade.timestampEpochMs}|${trade.symbol}|${trade.side}|" +\n                            "qty=${trade.quantity}|price=${trade.priceEur}|fee=${trade.feeEur}|" +\n                            "realizedPnl=${trade.realizedPnlEur}|paper=${trade.paper}|" +\n                            "score=${trade.aiScore}|reason=${sanitize(trade.aiReason)}"\n                    )\n                }\n                appendLine()\n\n                appendLine("[RECENT_STATUS_LOG]")\n                recentStatus.take(500).forEach { appendLine(sanitize(it)) }\n                appendLine()\n\n                appendLine("[PRIVACY]")\n                appendLine("API keys, exchange secrets, Telegram tokens, Discord tokens/webhooks, news API keys and remote-command PINs are never intentionally exported.")\n            }\n\n            val requested = customDirectoryPath.trim()\n            val filename = "cts_full_diagnostics_${System.currentTimeMillis()}.txt"\n\n            val result = if (requested.startsWith("content://")) {\n                val treeUri = Uri.parse(requested)\n                val resolver = appContext.contentResolver\n                val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(\n                    treeUri,\n                    DocumentsContract.getTreeDocumentId(treeUri)\n                )\n                val documentUri = DocumentsContract.createDocument(\n                    resolver,\n                    parentDocumentUri,\n                    "text/plain",\n                    filename\n                ) ?: error("Android folder picker did not return a writable diagnostics document URI.")\n\n                resolver.openOutputStream(documentUri, "w")?.use { stream ->\n                    stream.write(report.toByteArray(Charsets.UTF_8))\n                    stream.flush()\n                } ?: error("Could not open the selected diagnostics folder for writing.")\n\n                buildString {\n                    appendLine("DIAGNOSTICS SAVED SUCCESSFULLY")\n                    appendLine("fileUri=$documentUri")\n                    appendLine("directoryUri=$treeUri")\n                    appendLine("checks=${verification.size}")\n                    appendLine("failures=${verification.count { it.startsWith("FAIL") }}")\n                    appendLine("warnings=${verification.count { it.startsWith("WARN") }}")\n                    appendLine("sizeBytes=${report.toByteArray(Charsets.UTF_8).size}")\n                }.trim()\n            } else {\n                val defaultDiagnosticsDir = java.io.File(appContext.getExternalFilesDir(null), "diagnostics")\n                val requestedDir = if (requested.isNotBlank()) java.io.File(requested) else defaultDiagnosticsDir\n                if (!requestedDir.exists()) requestedDir.mkdirs()\n                if (!requestedDir.exists() || !requestedDir.isDirectory || !requestedDir.canWrite()) {\n                    defaultDiagnosticsDir.mkdirs()\n                    updateStatus(\n                        "Custom diagnostics directory is not writable. Falling back to ${defaultDiagnosticsDir.absolutePath}",\n                        "WARN"\n                    )\n                }\n                val finalDir = if (requestedDir.exists() && requestedDir.isDirectory && requestedDir.canWrite()) {\n                    requestedDir\n                } else {\n                    defaultDiagnosticsDir\n                }\n                val file = java.io.File(finalDir, filename)\n                file.writeText(report)\n                buildString {\n                    appendLine("DIAGNOSTICS SAVED SUCCESSFULLY")\n                    appendLine("file=${file.absolutePath}")\n                    appendLine("directory=${file.parentFile?.absolutePath ?: ""}")\n                    appendLine("checks=${verification.size}")\n                    appendLine("failures=${verification.count { it.startsWith("FAIL") }}")\n                    appendLine("warnings=${verification.count { it.startsWith("WARN") }}")\n                    appendLine("sizeBytes=${file.length()}")\n                }.trim()\n            }\n\n            updateStatus("Full app diagnostics saved: $filename", "INFO")\n            verification to result\n        } catch (error: Exception) {\n            val message = "Diagnostics export failed: ${error.message}"\n            updateStatus(message, "ERROR")\n            verification to message\n        }\n    }\n'
NEW_SYSTEM_SCREEN = '@Composable\nfun PreviewSystemTestScreen(\n    settings: BotSettings,\n    lines: List<String>,\n    diagnosticsDirectoryPath: String,\n    onDiagnosticsDirectoryPathChanged: (String) -> Unit,\n    onOpen: (AppTab) -> Unit,\n    onRun: () -> Unit,\n    onRunAndSave: (String, (String) -> Unit) -> Unit\n) {\n    val context = androidx.compose.ui.platform.LocalContext.current\n    var diagnosticsStatus by remember { mutableStateOf("Ready") }\n    var selectedDiagnosticsDirectory by remember(diagnosticsDirectoryPath) { mutableStateOf(diagnosticsDirectoryPath) }\n    val diagnosticsFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->\n        uri?.let { selected ->\n            runCatching {\n                context.contentResolver.takePersistableUriPermission(\n                    selected,\n                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION\n                )\n            }\n            selectedDiagnosticsDirectory = selected.toString()\n            onDiagnosticsDirectoryPathChanged(selectedDiagnosticsDirectory)\n            diagnosticsStatus = "Diagnostics destination selected"\n        }\n    }\n\n    val pass = lines.count { it.startsWith("PASS") }\n    val fail = lines.count { it.startsWith("FAIL") }\n    val warn = lines.count { it.startsWith("WARN") }\n    val total = max(1, pass + fail + warn)\n    val score = ((pass * 100f) / total).coerceIn(0f, 100f)\n    val healthy = fail == 0 && lines.isNotEmpty()\n\n    LazyColumn(\n        modifier = Modifier.fillMaxSize().background(PreviewBackground).padding(horizontal = 12.dp),\n        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),\n        verticalArrangement = Arrangement.spacedBy(8.dp)\n    ) {\n        item {\n            PreviewSettingsSegment(listOf("Backup & Recovery", "System"), 1) {\n                if (it == 0) onOpen(AppTab.BACKUP)\n            }\n        }\n        item {\n            PreviewCardBox {\n                Column {\n                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {\n                        Icon(\n                            if (healthy) Icons.Rounded.Security else Icons.Rounded.WarningAmber,\n                            null,\n                            tint = if (healthy) PreviewGreen else PreviewOrange,\n                            modifier = Modifier.size(35.dp)\n                        )\n                        Spacer(Modifier.width(10.dp))\n                        Text(\n                            if (healthy) "ALL SYSTEMS OPERATIONAL"\n                            else if (lines.isEmpty()) "SYSTEM DIAGNOSTICS NOT RUN"\n                            else "SYSTEM CHECK REQUIRED",\n                            color = if (healthy) PreviewGreen else PreviewOrange,\n                            fontSize = 16.sp,\n                            fontWeight = FontWeight.Bold,\n                            modifier = Modifier.weight(1f)\n                        )\n                    }\n                    Spacer(Modifier.height(14.dp))\n                    Row(Modifier.fillMaxWidth()) {\n                        Text("Last Full Test", color = PreviewMuted, fontSize = 9.sp, modifier = Modifier.weight(1f))\n                        Text(\n                            if (lines.isEmpty()) "—" else previewTimeFormatter.format(java.time.ZonedDateTime.now()),\n                            color = PreviewText,\n                            fontSize = 9.sp\n                        )\n                    }\n                    Spacer(Modifier.height(9.dp))\n                    Row(Modifier.fillMaxWidth()) {\n                        Text("Overall Health", color = PreviewMuted, fontSize = 9.sp, modifier = Modifier.weight(1f))\n                        Text(\n                            "${score.toInt()}%",\n                            color = if (healthy) PreviewGreen else PreviewOrange,\n                            fontSize = 16.sp,\n                            fontWeight = FontWeight.Bold\n                        )\n                    }\n                    LinearProgressIndicator(\n                        progress = score / 100f,\n                        modifier = Modifier.fillMaxWidth().height(5.dp),\n                        color = if (healthy) PreviewGreen else PreviewOrange,\n                        trackColor = PreviewDivider\n                    )\n                }\n            }\n        }\n        item {\n            PreviewCardBox {\n                Column {\n                    PreviewSectionHeader("Systems")\n                    Spacer(Modifier.height(5.dp))\n                    if (lines.isEmpty()) {\n                        SystemLine("Settings & Persistence", "PENDING")\n                        SystemLine("News & Data Providers", "PENDING")\n                        SystemLine("AI & Research Engine", "PENDING")\n                        SystemLine("M3 Governance", "PENDING")\n                        SystemLine("M4 Execution Guard", "PENDING")\n                        SystemLine("Lifecycle & Risk", "PENDING")\n                        SystemLine("Learning & Journal", "PENDING")\n                        SystemLine("CloudShare & Recovery", "PENDING")\n                    } else {\n                        lines.take(24).forEach { line ->\n                            val parts = line.split("|").map { it.trim() }\n                            SystemLine(parts.getOrNull(1) ?: line.take(40), parts.firstOrNull() ?: "INFO")\n                        }\n                    }\n                    Spacer(Modifier.height(8.dp))\n                    PreviewOutlineButton(\n                        if (lines.isEmpty()) "Run Full System Test" else "Run System Test Again",\n                        onRun,\n                        Modifier.fillMaxWidth()\n                    )\n                }\n            }\n        }\n        item {\n            PreviewCardBox {\n                Column {\n                    PreviewSectionHeader("Full App Diagnostics")\n                    Spacer(Modifier.height(7.dp))\n                    SettingsValueRow(\n                        "Diagnostics Folder",\n                        selectedDiagnosticsDirectory.ifBlank { "Default app diagnostics folder" }\n                    )\n                    SettingsValueRow(\n                        "Report Contents",\n                        "System + runtime + trading + provider health"\n                    )\n                    Spacer(Modifier.height(7.dp))\n                    PreviewOutlineButton(\n                        "Select Diagnostics Folder",\n                        { diagnosticsFolderPicker.launch(null) },\n                        Modifier.fillMaxWidth()\n                    )\n                    Spacer(Modifier.height(7.dp))\n                    PreviewPrimaryButton(\n                        "Run & Save Full Diagnostics",\n                        {\n                            diagnosticsStatus = "Running full diagnostics..."\n                            onRunAndSave(selectedDiagnosticsDirectory) { result ->\n                                diagnosticsStatus = result\n                            }\n                        },\n                        Modifier.fillMaxWidth()\n                    )\n                    Spacer(Modifier.height(9.dp))\n                    Text(\n                        diagnosticsStatus,\n                        color = if (diagnosticsStatus.contains("fail", true) || diagnosticsStatus.contains("error", true)) PreviewRed else PreviewGreen,\n                        fontSize = 9.sp,\n                        maxLines = 6,\n                        overflow = TextOverflow.Ellipsis\n                    )\n                    Spacer(Modifier.height(6.dp))\n                    Text(\n                        "The saved report excludes API keys, exchange secrets, Telegram/Discord credentials, news API keys and remote-control PINs.",\n                        color = PreviewMuted,\n                        fontSize = 8.sp\n                    )\n                }\n            }\n        }\n        item {\n            Text(\n                "Mode ${settings.mode.name.replace(\'_\', \' \')} • Provider ${settings.exchangeProvider.name.replace(\'_\', \' \')}",\n                color = PreviewMuted,\n                fontSize = 9.sp,\n                modifier = Modifier.padding(horizontal = 4.dp)\n            )\n        }\n    }\n}'

def fail(message: str) -> None:
    raise SystemExit(f"[CTS system diagnostics UI] {message}")

def require(path: Path) -> None:
    if not path.exists():
        fail(f"Required file missing: {path}")

def patch_settings_store(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if "fun diagnosticsDirectoryPath()" not in text:
        anchor = """    fun backupDirectoryPath(): String = prefs.getString("backup_directory_path", "") ?: ""
    fun saveBackupDirectoryPath(path: String) {
        prefs.edit().putString("backup_directory_path", path.trim()).apply()
    }
"""
        replacement = anchor + """
    fun diagnosticsDirectoryPath(): String = prefs.getString("diagnostics_directory_path", "") ?: ""
    fun saveDiagnosticsDirectoryPath(path: String) {
        prefs.edit().putString("diagnostics_directory_path", path.trim()).apply()
    }
"""
        if anchor not in text:
            fail("AppSettingsStore backup-directory anchor changed")
        text = text.replace(anchor, replacement, 1)
    path.write_text(text, encoding="utf-8")

def patch_controller(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if "suspend fun exportFullDiagnosticsToFile(" not in text:
        anchor = "\n\n    suspend fun processRemoteCommands(settings: BotSettings = settingsStore.load()): List<String> {"
        if anchor not in text:
            fail("BotController processRemoteCommands anchor changed")
        text = text.replace(anchor, CONTROLLER_METHOD + anchor, 1)
    path.write_text(text, encoding="utf-8")

def patch_preview_ui(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    text = text.replace('AppTab.SYSTEM_TEST -> "System Test (v4 Systems)"', 'AppTab.SYSTEM_TEST -> "System Diagnostics"')
    text = text.replace('SettingsNavRow("System Test") { onOpen(AppTab.SYSTEM_TEST) }', 'SettingsNavRow("System Diagnostics") { onOpen(AppTab.SYSTEM_TEST) }')
    text = text.replace('AppTab.SYSTEM_TEST to "System Test"', 'AppTab.SYSTEM_TEST to "System Diagnostics"')

    region = text.split("fun PreviewBackupRecoveryScreen(", 1)[1].split(") {", 1)[0] if "fun PreviewBackupRecoveryScreen(" in text else ""
    if "onOpen: (AppTab) -> Unit," not in region:
        anchor = """fun PreviewBackupRecoveryScreen(
    settings: BotSettings,
    backupDirectoryPath: String,
"""
        replacement = """fun PreviewBackupRecoveryScreen(
    settings: BotSettings,
    onOpen: (AppTab) -> Unit,
    backupDirectoryPath: String,
"""
        if anchor not in text:
            fail("PreviewBackupRecoveryScreen signature anchor changed")
        text = text.replace(anchor, replacement, 1)

    dead = 'item { PreviewSettingsSegment(listOf("Backup & Recovery", "System"), 0) { if (it == 1) status = "Open System Test from Settings" } }'
    live = 'item { PreviewSettingsSegment(listOf("Backup & Recovery", "System"), 0) { if (it == 1) onOpen(AppTab.SYSTEM_TEST) } }'
    if dead in text:
        text = text.replace(dead, live, 1)
    elif live not in text:
        fail("Backup/System segment anchor changed")

    backup_region = text.split("fun PreviewBackupRecoveryScreen(", 1)[1].split("@Composable\nfun PreviewSystemTestScreen", 1)[0]

    if "var selectedBackupDirectory by remember(backupDirectoryPath)" not in backup_region:
        state_anchor = """    val cloudStore = remember { CloudShareSettingsStore(context) }
    var status by remember { mutableStateOf("Ready") }
    var selectedRestore by remember { mutableStateOf("") }
"""
        state_replacement = """    val cloudStore = remember { CloudShareSettingsStore(context) }
    var status by remember { mutableStateOf("Ready") }
    var selectedBackupDirectory by remember(backupDirectoryPath) { mutableStateOf(backupDirectoryPath) }
    var selectedRestore by remember { mutableStateOf("") }
"""
        if state_anchor not in text:
            fail("Backup live-directory state anchor changed")
        text = text.replace(state_anchor, state_replacement, 1)

    backup_region = text.split("fun PreviewBackupRecoveryScreen(", 1)[1].split("@Composable\nfun PreviewSystemTestScreen", 1)[0]
    if "takePersistableUriPermission" not in backup_region:
        old = """        uri?.let { selected ->
            onBackupDirectoryPathChanged(selected.toString())
            status = "Backup destination selected"
        }
"""
        new = """        uri?.let { selected ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    selected,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            selectedBackupDirectory = selected.toString()
            onBackupDirectoryPathChanged(selectedBackupDirectory)
            status = "Backup destination selected"
        }
"""
        if old not in text:
            fail("Backup folder-picker callback anchor changed")
        text = text.replace(old, new, 1)
    elif "selectedBackupDirectory = selected.toString()" not in backup_region:
        old = """            onBackupDirectoryPathChanged(selected.toString())
            status = "Backup destination selected"
"""
        new = """            selectedBackupDirectory = selected.toString()
            onBackupDirectoryPathChanged(selectedBackupDirectory)
            status = "Backup destination selected"
"""
        if old not in text:
            fail("Backup persisted-picker state anchor changed")
        text = text.replace(old, new, 1)

    text = text.replace(
        'SettingsValueRow("Backup Folder", backupDirectoryPath.ifBlank { "Default app backup folder" })',
        'SettingsValueRow("Backup Folder", selectedBackupDirectory.ifBlank { "Default app backup folder" })',
        1
    )
    text = text.replace(
        'onExportFullBackup(backupDirectoryPath) { result -> status = result }',
        'onExportFullBackup(selectedBackupDirectory) { result -> status = result }',
        1
    )

    start = text.find("@Composable\nfun PreviewSystemTestScreen(")
    if start < 0:
        fail("PreviewSystemTestScreen not found")
    end = text.find("\n@Composable\n", start + 20)
    if end < 0:
        fail("Could not locate PreviewSystemTestScreen end")
    text = text[:start] + NEW_SYSTEM_SCREEN + text[end:]
    path.write_text(text, encoding="utf-8")

def patch_main_activity(path: Path) -> None:
    text = path.read_text(encoding="utf-8")

    if "onExportDiagnostics = { settings, customDiagnosticsDirectory, callback ->" not in text:
        anchor = """                        onRunSystemTest = { settings, callback ->
                            lifecycleScope.launch {
                                callback(controller.runSystemFeatureVerification(settings))
                            }
                        },
                        onExportFullBackup = { settings, customBackupDirectory, callback ->
"""
        replacement = """                        onRunSystemTest = { settings, callback ->
                            lifecycleScope.launch {
                                callback(controller.runSystemFeatureVerification(settings))
                            }
                        },
                        onExportDiagnostics = { settings, customDiagnosticsDirectory, callback ->
                            lifecycleScope.launch {
                                settingsStore.saveDiagnosticsDirectoryPath(customDiagnosticsDirectory)
                                callback(controller.exportFullDiagnosticsToFile(settings, customDiagnosticsDirectory))
                            }
                        },
                        onExportFullBackup = { settings, customBackupDirectory, callback ->
"""
        if anchor not in text:
            fail("MainActivity diagnostics callback anchor changed")
        text = text.replace(anchor, replacement, 1)

    if "onExportDiagnostics: (BotSettings, String, (Pair<List<String>, String>) -> Unit) -> Unit" not in text:
        anchor = """    onRunSystemTest: (BotSettings, (List<String>) -> Unit) -> Unit,
    onExportFullBackup: (BotSettings, String, (String) -> Unit) -> Unit,
"""
        replacement = """    onRunSystemTest: (BotSettings, (List<String>) -> Unit) -> Unit,
    onExportDiagnostics: (BotSettings, String, (Pair<List<String>, String>) -> Unit) -> Unit,
    onExportFullBackup: (BotSettings, String, (String) -> Unit) -> Unit,
"""
        if anchor not in text:
            fail("AdvancedBotApp diagnostics signature anchor changed")
        text = text.replace(anchor, replacement, 1)

    if "diagnosticsDirectoryPath = store.diagnosticsDirectoryPath()" not in text:
        old = """                AppTab.SYSTEM_TEST -> PreviewSystemTestScreen(
                    settings = settings,
                    lines = systemTestLines,
                    onRun = {
                        onRunSystemTest(settings) { result ->
                            systemTestLines = result
                            statusStore.write("System test manually completed. rows=${result.size}", "INFO")
                            status = "System test complete"
                        }
                    }
                )
"""
        new = """                AppTab.SYSTEM_TEST -> PreviewSystemTestScreen(
                    settings = settings,
                    lines = systemTestLines,
                    diagnosticsDirectoryPath = store.diagnosticsDirectoryPath(),
                    onDiagnosticsDirectoryPathChanged = { store.saveDiagnosticsDirectoryPath(it) },
                    onOpen = { currentTab = it },
                    onRun = {
                        onRunSystemTest(settings) { result ->
                            systemTestLines = result
                            statusStore.write("System test manually completed. rows=${result.size}", "INFO")
                            status = "System test complete"
                        }
                    },
                    onRunAndSave = { directory, callback ->
                        onExportDiagnostics(settings, directory) { result ->
                            systemTestLines = result.first
                            callback(result.second)
                            statusStore.write(
                                "Full diagnostics export completed. rows=${result.first.size}",
                                if (result.second.contains("failed", true)) "ERROR" else "INFO"
                            )
                            status = if (result.second.contains("failed", true)) "Diagnostics export failed" else "Diagnostics saved"
                        }
                    }
                )
"""
        if old not in text:
            fail("PreviewSystemTestScreen route anchor changed")
        text = text.replace(old, new, 1)

    wanted = """                AppTab.BACKUP -> PreviewBackupRecoveryScreen(
                    settings = settings,
                    onOpen = { currentTab = it },
"""
    if wanted not in text:
        anchor = """                AppTab.BACKUP -> PreviewBackupRecoveryScreen(
                    settings = settings,
                    backupDirectoryPath = store.backupDirectoryPath(),
"""
        replacement = """                AppTab.BACKUP -> PreviewBackupRecoveryScreen(
                    settings = settings,
                    onOpen = { currentTab = it },
                    backupDirectoryPath = store.backupDirectoryPath(),
"""
        if anchor not in text:
            fail("PreviewBackupRecoveryScreen route anchor changed")
        text = text.replace(anchor, replacement, 1)

    path.write_text(text, encoding="utf-8")

def validate(repo: Path) -> None:
    root = repo / "app/src/main/java/com/ksp/cryptobot"
    main = (root / "MainActivity.kt").read_text(encoding="utf-8")
    ui = (root / "PreviewReplicaUi.kt").read_text(encoding="utf-8")
    controller = (root / "core/BotController.kt").read_text(encoding="utf-8")
    store = (root / "settings/AppSettingsStore.kt").read_text(encoding="utf-8")
    checks = {
        "Backup System segment navigates": 'if (it == 1) onOpen(AppTab.SYSTEM_TEST)' in ui,
        "System Backup segment navigates": 'if (it == 0) onOpen(AppTab.BACKUP)' in ui,
        "System Diagnostics title": 'AppTab.SYSTEM_TEST -> "System Diagnostics"' in ui,
        "diagnostics folder picker": 'Select Diagnostics Folder' in ui,
        "diagnostics selected folder used immediately": 'selectedDiagnosticsDirectory by remember(diagnosticsDirectoryPath)' in ui and 'onRunAndSave(selectedDiagnosticsDirectory)' in ui,
        "backup selected folder used immediately": 'selectedBackupDirectory by remember(backupDirectoryPath)' in ui and 'onExportFullBackup(selectedBackupDirectory)' in ui,
        "run and save diagnostics button": 'Run & Save Full Diagnostics' in ui,
        "diagnostics directory persisted": 'fun diagnosticsDirectoryPath()' in store,
        "controller diagnostics export": 'suspend fun exportFullDiagnosticsToFile(' in controller,
        "privacy redaction": 'secrets=EXCLUDED_AND_REDACTED' in controller and '[REDACTED]' in controller,
        "runtime log exported": '[RECENT_STATUS_LOG]' in controller,
        "positions exported": '[LIFECYCLE_POSITIONS]' in controller,
        "trades exported": '[RECENT_TRADES]' in controller,
        "provider health exported": '[NEWS_PROVIDER_HEALTH]' in controller,
        "MainActivity System UI wiring": 'diagnosticsDirectoryPath = store.diagnosticsDirectoryPath()' in main,
    }
    for name, ok in checks.items():
        print(("PASS" if ok else "FAIL") + " | " + name)
    failed = [name for name, ok in checks.items() if not ok]
    if failed:
        fail("validation failed: " + ", ".join(failed))

def main() -> None:
    repo = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd().resolve()
    root = repo / "app/src/main/java/com/ksp/cryptobot"
    paths = [
        root / "settings/AppSettingsStore.kt",
        root / "core/BotController.kt",
        root / "PreviewReplicaUi.kt",
        root / "MainActivity.kt",
    ]
    for path in paths:
        require(path)
    patch_settings_store(paths[0])
    patch_controller(paths[1])
    patch_preview_ui(paths[2])
    patch_main_activity(paths[3])
    validate(repo)
    print("[CTS system diagnostics UI] Applied successfully.")

if __name__ == "__main__":
    main()
