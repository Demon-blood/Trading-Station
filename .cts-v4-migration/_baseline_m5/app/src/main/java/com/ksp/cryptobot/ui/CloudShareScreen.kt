package com.ksp.cryptobot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ksp.cryptobot.cloudshare.CloudShareSettingsStore
import com.ksp.cryptobot.cloudshare.CloudShareSyncEngine
import kotlinx.coroutines.launch

/**
 * Standalone Compose screen for v4 CloudShare. MainActivity navigation wiring is
 * intentionally a separate integration step because the current app keeps most
 * navigation in a very large MainActivity.kt.
 */
@Composable
fun CloudShareScreen() {
    val context = LocalContext.current
    val store = remember { CloudShareSettingsStore(context) }
    val engine = remember { CloudShareSyncEngine(context) }
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(store.enabled) }
    var workerUrl by remember { mutableStateOf(store.apiUrl) }
    var inviteCode by remember { mutableStateOf("") }
    var adminToken by remember { mutableStateOf(store.adminToken()) }
    var adminInviteLabel by remember { mutableStateOf("Android invitation") }
    var status by remember { mutableStateOf("CloudShare ready for configuration.") }
    var busy by remember { mutableStateOf(false) }
    var collectiveEnabled by remember { mutableStateOf(store.collectiveLearningEnabled) }
    var minSamples by remember { mutableStateOf(store.collectiveMinSamples.toString()) }
    var maxAdjustment by remember { mutableStateOf(store.collectiveMaxAdjustment.toString()) }
    var collectiveWeight by remember { mutableStateOf(store.collectiveWeight.toString()) }
    var emitAggregates by remember { mutableStateOf(store.emitSharedAggregates) }
    var backfillEnabled by remember { mutableStateOf(store.backfillEnabled) }

    fun runAction(block: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            status = runCatching { block() }.fold(
                onSuccess = { it },
                onFailure = { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
            )
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("CloudShare", style = MaterialTheme.typography.headlineMedium)
        Text("Shared desktop + Android trading intelligence via the existing Cloudflare Worker/D1/R2 backend.")

        OutlinedTextField(
            value = workerUrl,
            onValueChange = { workerUrl = it },
            label = { Text("Worker HTTPS URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                store.apiUrl = workerUrl
                status = "Worker URL saved."
            }, enabled = !busy) { Text("Save URL") }
            Button(onClick = {
                store.apiUrl = workerUrl
                runAction { "Health: ${engine.health()}" }
            }, enabled = !busy && workerUrl.startsWith("https://")) { Text("Health") }
        }

        HorizontalDivider()
        Text("Join shared intelligence", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = inviteCode,
            onValueChange = { inviteCode = it },
            label = { Text("Invitation code") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Button(onClick = {
            store.apiUrl = workerUrl
            runAction {
                val credentials = engine.register(inviteCode)
                enabled = true
                "Registered. client=${credentials.clientId}, contributor=${credentials.contributorId}"
            }
        }, enabled = !busy && inviteCode.isNotBlank() && workerUrl.startsWith("https://")) {
            Text("Join CloudShare")
        }

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    store.enabled = it
                    status = if (it) "Automatic CloudShare sync enabled." else "CloudShare sync disabled."
                }
            )
            Spacer(Modifier.width(8.dp))
            Text(if (enabled) "Automatic sync enabled" else "Automatic sync disabled")
        }
        Button(onClick = {
            store.apiUrl = workerUrl
            runAction {
                val result = engine.syncIfDue(force = true)
                "Sync: recent=${result.recentQueued}, aggregates=${result.aggregatesQueued}, backfill=${result.backfilled}, uploaded=${result.uploaded}, duplicates=${result.duplicates}, rejected=${result.rejected}, downloaded=${result.downloaded}, collective=${result.collectiveOutcomeRows}${if (result.error.isBlank()) "" else ", error=${result.error}"}"
            }
        }, enabled = !busy && enabled) { Text("Sync now") }

        HorizontalDivider()
        Text("Collective Learning", style = MaterialTheme.typography.titleMedium)
        Text("Downloaded shared outcomes are advisory and bounded. They never bypass live-trading safety gates.")
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(checked = collectiveEnabled, onCheckedChange = { collectiveEnabled = it })
            Spacer(Modifier.width(8.dp))
            Text("Use collective score adjustment")
        }
        OutlinedTextField(
            value = minSamples, onValueChange = { minSamples = it.filter(Char::isDigit) },
            label = { Text("Minimum matching outcome samples") }, modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        OutlinedTextField(
            value = maxAdjustment, onValueChange = { maxAdjustment = it.filter(Char::isDigit) },
            label = { Text("Maximum score adjustment (points)") }, modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        OutlinedTextField(
            value = collectiveWeight, onValueChange = { collectiveWeight = it },
            label = { Text("Collective weight (0.0–2.0)") }, modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(checked = emitAggregates, onCheckedChange = { emitAggregates = it })
            Spacer(Modifier.width(8.dp))
            Text("Publish compact shared aggregates")
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(checked = backfillEnabled, onCheckedChange = { backfillEnabled = it })
            Spacer(Modifier.width(8.dp))
            Text("Backfill historical Android evidence")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                store.collectiveLearningEnabled = collectiveEnabled
                store.collectiveMinSamples = minSamples.toIntOrNull() ?: 25
                store.collectiveMaxAdjustment = maxAdjustment.toIntOrNull() ?: 6
                store.collectiveWeight = collectiveWeight.toDoubleOrNull() ?: 1.0
                store.emitSharedAggregates = emitAggregates
                store.backfillEnabled = backfillEnabled
                runAction {
                    engine.refreshCollectiveCache()
                    "Collective settings saved. ${engine.diagnostics()}"
                }
            }, enabled = !busy) { Text("Save collective") }
            OutlinedButton(onClick = {
                runAction { "Diagnostics: ${engine.diagnostics()}" }
            }, enabled = !busy) { Text("Diagnostics") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                runAction {
                    engine.resetBackfill()
                    "Historical backfill reset. It will resume from the oldest local rows."
                }
            }, enabled = !busy) { Text("Reset backfill") }
            Button(onClick = {
                runAction { "Bootstrap upload: ${engine.createAndUploadBootstrap()}" }
            }, enabled = !busy && enabled) { Text("Upload bootstrap") }
        }

        HorizontalDivider()
        Text("Owner / Admin", style = MaterialTheme.typography.titleMedium)
        Text("Optional. The owner token remains encrypted in Android Keystore storage.")
        OutlinedTextField(
            value = adminToken,
            onValueChange = { adminToken = it },
            label = { Text("CloudShare admin token") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                store.saveAdminToken(adminToken)
                runAction { "Admin ping: ${engine.adminPing()}" }
            }, enabled = !busy && adminToken.isNotBlank()) { Text("Verify owner") }
            OutlinedButton(onClick = {
                store.clearAdminToken()
                adminToken = ""
                status = "Owner token removed from this device."
            }, enabled = !busy) { Text("Forget owner") }
        }

        OutlinedTextField(
            value = adminInviteLabel,
            onValueChange = { adminInviteLabel = it },
            label = { Text("New invitation label") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Button(onClick = {
            store.saveAdminToken(adminToken)
            runAction { "Invitation: ${engine.adminCreateInvite(adminInviteLabel)}" }
        }, enabled = !busy && adminToken.isNotBlank()) { Text("Create invitation") }

        HorizontalDivider()
        Text("Status", style = MaterialTheme.typography.titleMedium)
        Text(status)
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}
