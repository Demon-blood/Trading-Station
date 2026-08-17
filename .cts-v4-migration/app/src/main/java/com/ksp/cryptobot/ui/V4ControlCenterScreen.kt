package com.ksp.cryptobot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ksp.cryptobot.cloudshare.CloudShareSettingsStore
import com.ksp.cryptobot.cloudshare.CloudShareSyncEngine
import com.ksp.cryptobot.core.BotController
import com.ksp.cryptobot.data.AppDatabase
import com.ksp.cryptobot.release.V4MigrationBackupManager
import com.ksp.cryptobot.release.V4MaintenanceManager
import com.ksp.cryptobot.release.V4ReleaseInfo
import com.ksp.cryptobot.release.V4SystemVerifier
import com.ksp.cryptobot.release.V4VerificationItem
import com.ksp.cryptobot.research.ResearchSettingsStore
import kotlinx.coroutines.launch

private enum class V4Panel(val label: String) { OVERVIEW("Overview"), CLOUDSHARE("CloudShare"), RESEARCH("Research"), RECOVERY("Recovery") }

@Composable
fun V4ControlCenterScreen() {
    val context = LocalContext.current
    var panel by remember { mutableStateOf(V4Panel.OVERVIEW) }
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = V4Panel.values().indexOf(panel), edgePadding = 8.dp) {
            V4Panel.values().forEach { item ->
                Tab(selected = panel == item, onClick = { panel = item }, text = { Text(item.label) })
            }
        }
        when (panel) {
            V4Panel.OVERVIEW -> V4OverviewPanel()
            V4Panel.CLOUDSHARE -> CloudShareScreen()
            V4Panel.RESEARCH -> V4ResearchPanel()
            V4Panel.RECOVERY -> V4RecoveryPanel()
        }
    }
}

@Composable
private fun V4OverviewPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val verifier = remember { V4SystemVerifier(context) }
    val controller = remember { BotController(context.applicationContext) }
    val database = remember { AppDatabase.get(context.applicationContext) }
    val cloudStore = remember { CloudShareSettingsStore(context) }
    val cloudEngine = remember { CloudShareSyncEngine(context) }
    var checks by remember { mutableStateOf<List<V4VerificationItem>>(emptyList()) }
    var summary by remember { mutableStateOf("Stage 6/6 final integration ready.") }
    var busy by remember { mutableStateOf(false) }

    fun verify() {
        if (busy) return
        busy = true
        scope.launch {
            val settings = com.ksp.cryptobot.settings.AppSettingsStore(context).load()
            checks = runCatching { verifier.verify(settings) }.getOrElse { listOf(V4VerificationItem("FAIL", "V4 verifier", it.message ?: it.javaClass.simpleName)) }
            val fail = checks.count { it.status == "FAIL" }
            val warn = checks.count { it.status == "WARN" }
            summary = "V4 verification: ${checks.size - fail - warn} PASS, $warn WARN, $fail FAIL"
            busy = false
        }
    }

    LaunchedEffect(Unit) { verify() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Crypto TradeStation v4", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        Text("${V4ReleaseInfo.MIGRATION_STAGE_COMPLETE}/${V4ReleaseInfo.MIGRATION_STAGE_COUNT} migration stages integrated • Room ${V4ReleaseInfo.ROOM_SCHEMA_VERSION} • CloudShare ${V4ReleaseInfo.CLOUDSHARE_PROTOCOL}")
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Final architecture", fontWeight = FontWeight.Bold)
                V4ReleaseInfo.stages.forEachIndexed { index, stage -> Text("✓ ${index + 1}. $stage") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { verify() }, enabled = !busy) { Text("Verify v4") }
            OutlinedButton(onClick = {
                if (!busy) { busy = true; scope.launch {
                    val result = runCatching { cloudEngine.syncIfDue(force = true) }.getOrNull()
                    summary = if (!cloudStore.enabled) "CloudShare is disabled; local operation is unaffected."
                    else result?.let { "CloudShare sync: uploaded=${it.uploaded}, downloaded=${it.downloaded}, collective=${it.collectiveOutcomeRows}${if (it.error.isBlank()) "" else ", error=${it.error}"}" } ?: "CloudShare sync failed."
                    busy = false
                } }
            }, enabled = !busy) { Text("Sync CloudShare") }
            OutlinedButton(onClick = {
                if (!busy) { busy = true; scope.launch {
                    val lines = runCatching { controller.runSystemFeatureVerification() }.getOrElse { listOf("FAIL | System test | ${it.message}") }
                    summary = "Full System Test: PASS=${lines.count { it.startsWith("PASS") }}, WARN=${lines.count { it.startsWith("WARN") }}, FAIL=${lines.count { it.startsWith("FAIL") }}"
                    busy = false
                } }
            }, enabled = !busy) { Text("Full System Test") }
        }
        Text(summary, fontWeight = FontWeight.Bold)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        checks.forEach { check -> VerificationCard(check) }
        val dbVersion = runCatching { database.openHelper.readableDatabase.version }.getOrDefault(-1)
        Text("Local database schema: $dbVersion", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun VerificationCard(check: V4VerificationItem) {
    val color = when (check.status) {
        "PASS" -> MaterialTheme.colorScheme.secondary
        "WARN" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("${check.status} • ${check.name}", color = color, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp)); Text(check.detail)
        }
    }
}

@Composable
private fun V4ResearchPanel() {
    val context = LocalContext.current
    val store = remember { ResearchSettingsStore(context) }
    var enabled by remember { mutableStateOf(store.enabled()) }
    var strategies by remember { mutableStateOf(store.advancedStrategiesEnabled()) }
    var walkForward by remember { mutableStateOf(store.walkForwardEnabled()) }
    var monteCarlo by remember { mutableStateOf(store.monteCarloEnabled()) }
    var sequence by remember { mutableStateOf(store.sequenceModelEnabled()) }
    var rl by remember { mutableStateOf(store.rlSandboxEnabled()) }
    var futures by remember { mutableStateOf(store.futuresContextEnabled()) }
    var wallets by remember { mutableStateOf(store.labeledWalletEnabled()) }
    var paperPromotion by remember { mutableStateOf(store.researchPromotionInPaper()) }
    var livePromotion by remember { mutableStateOf(store.researchPromotionInLive()) }
    var positive by remember { mutableStateOf(store.maxPositiveAdjustment().toString()) }
    var negative by remember { mutableStateOf(store.maxNegativeAdjustment().toString()) }
    var simulations by remember { mutableStateOf(store.monteCarloSimulations().toString()) }
    var samples by remember { mutableStateOf(store.minimumOutcomeSamples().toString()) }
    var whaleKey by remember { mutableStateOf(store.whaleAlertApiKey()) }
    var whaleMin by remember { mutableStateOf(store.whaleAlertMinUsd().toString()) }
    var whaleRisk by remember { mutableStateOf(store.whaleAlertExchangeRiskUsd().toString()) }
    var whaleOutflow by remember { mutableStateOf(store.whaleAlertExchangeOutflowBullUsd().toString()) }
    var status by remember { mutableStateOf("Research-created LIVE entries remain OFF by default.") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Stage 5 Research Controls", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Research runs before M3 governance and M4 execution. These switches cannot bypass hard live-trading safety gates.")
        ResearchToggle("Research ensemble", enabled) { enabled = it }
        ResearchToggle("23 advanced strategy votes", strategies) { strategies = it }
        ResearchToggle("Walk-forward validation", walkForward) { walkForward = it }
        ResearchToggle("Monte Carlo robustness", monteCarlo) { monteCarlo = it }
        ResearchToggle("Sequence model", sequence) { sequence = it }
        ResearchToggle("RL sandbox", rl) { rl = it }
        ResearchToggle("Kraken Futures context", futures) { futures = it }
        ResearchToggle("Labeled-wallet context", wallets) { wallets = it }
        ResearchToggle("Allow research promotion in PAPER", paperPromotion) { paperPromotion = it }
        ResearchToggle("Allow research-created LIVE entries", livePromotion) { livePromotion = it }
        if (livePromotion) Text("Warning: LIVE research promotion is enabled. M3/M4 guards still apply, but the recommended default is OFF.", color = MaterialTheme.colorScheme.error)
        OutlinedTextField(positive, { positive = it.filter(Char::isDigit) }, label = { Text("Max positive research adjustment (0–10)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(negative, { negative = it.filter(Char::isDigit) }, label = { Text("Max negative research adjustment (0–15)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(simulations, { simulations = it.filter(Char::isDigit) }, label = { Text("Monte Carlo simulations (100–5000)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(samples, { samples = it.filter(Char::isDigit) }, label = { Text("Minimum outcome samples (5–100)") }, modifier = Modifier.fillMaxWidth())
        HorizontalDivider()
        Text("Optional labeled-wallet / Whale Alert context", fontWeight = FontWeight.Bold)
        OutlinedTextField(whaleKey, { whaleKey = it }, label = { Text("Whale Alert API key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(whaleMin, { whaleMin = it.filter(Char::isDigit) }, label = { Text("Minimum labeled transfer USD") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(whaleRisk, { whaleRisk = it.filter(Char::isDigit) }, label = { Text("Exchange inflow risk threshold USD") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(whaleOutflow, { whaleOutflow = it.filter(Char::isDigit) }, label = { Text("Exchange outflow bullish threshold USD") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            store.setEnabled(enabled); store.setAdvancedStrategiesEnabled(strategies); store.setWalkForwardEnabled(walkForward)
            store.setMonteCarloEnabled(monteCarlo); store.setSequenceModelEnabled(sequence); store.setRlSandboxEnabled(rl)
            store.setFuturesContextEnabled(futures); store.setLabeledWalletEnabled(wallets)
            store.setResearchPromotionInPaper(paperPromotion); store.setResearchPromotionInLive(livePromotion)
            store.setMaxPositiveAdjustment(positive.toIntOrNull() ?: 6); store.setMaxNegativeAdjustment(negative.toIntOrNull() ?: 8)
            store.setMonteCarloSimulations(simulations.toIntOrNull() ?: 500); store.setMinimumOutcomeSamples(samples.toIntOrNull() ?: 10)
            store.saveWhaleAlertApiKey(whaleKey)
            store.setWhaleAlertMinUsd(whaleMin.toLongOrNull() ?: 500_000L)
            store.setWhaleAlertExchangeRiskUsd(whaleRisk.toLongOrNull() ?: 1_000_000L)
            store.setWhaleAlertExchangeOutflowBullUsd(whaleOutflow.toLongOrNull() ?: 1_000_000L)
            status = "Research settings saved. LIVE promotion=${store.researchPromotionInLive()}, WhaleAlertConfigured=${store.whaleAlertApiKey().isNotBlank()}."
        }) { Text("Save Research Settings") }
        Text(status)
    }
}

@Composable
private fun ResearchToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f)); Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun V4RecoveryPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { V4MigrationBackupManager(context) }
    val maintenance = remember { V4MaintenanceManager(context) }
    val controller = remember { BotController(context.applicationContext) }
    var input by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Core backup + v4 supplemental backup provides the complete migration recovery set.") }
    var busy by remember { mutableStateOf(false) }

    fun run(block: suspend () -> String) {
        if (busy) return; busy = true
        scope.launch { status = runCatching { block() }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }; busy = false }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Backup, Recovery & Diagnostics", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Core backup preserves normal CTS settings/trades/learning. The v4 supplemental file preserves governance, execution-quality, advanced-execution and research history. CloudShare tokens/admin credentials and research API keys are deliberately not exported by the supplemental backup.")
        Button(onClick = { run { controller.exportFullLocalBackupToFile() } }, enabled = !busy) { Text("Export Core Full Backup") }
        Button(onClick = { run { manager.exportSupplementalBackupToFile() } }, enabled = !busy) { Text("Export v4 Supplemental Backup") }
        OutlinedButton(onClick = { run { manager.exportDiagnosticsBundleToFile() } }, enabled = !busy) { Text("Export Redacted Diagnostics ZIP") }
        OutlinedButton(onClick = { run { maintenance.compact(365).detail } }, enabled = !busy) { Text("Compact v4 Operational Data") }
        Text("Compaction keeps core trades and learned profiles. It prunes v4 operational/research telemetry older than 365 days, old uploaded CloudShare outbox rows and old sync audit rows, then checkpoints/vacuums SQLite.")
        HorizontalDivider()
        Text("Restore v4 supplemental", fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth(), minLines = 3,
            label = { Text("File path, content:// URI, or raw supplemental JSON") }
        )
        ResearchToggle("Replace existing v4 operational/research history", replace) { replace = it }
        Button(onClick = { run { manager.restoreSupplementalBackup(input, replace) } }, enabled = !busy && input.isNotBlank()) { Text("Restore v4 Supplemental") }
        Text("For a complete device recovery: restore the Core Full Backup first, then restore the v4 Supplemental Backup. CloudShare credentials must be re-joined/re-entered on the new device.")
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedCard(Modifier.fillMaxWidth()) { Text(status, Modifier.padding(12.dp)) }
    }
}
