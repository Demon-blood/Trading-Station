package com.ksp.cryptobot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ksp.cryptobot.cloudshare.*
import kotlinx.coroutines.launch

private enum class CloudShareFlow { HOME, CREATE, JOIN, REPAIR, MANAGE }
private enum class CreateStep { WELCOME, TOKEN, ACCOUNT, REVIEW, PROVISIONING, COMPLETE }
private enum class JoinStep { WELCOME, WORKER, INVITE, OPTIONS, COMPLETE }

@Composable
fun CloudShareScreen() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val store = remember { CloudShareSettingsStore(context) }
    val engine = remember { CloudShareSyncEngine(context) }
    val provisioner = remember { CloudShareProvisioner(context) }
    val scope = rememberCoroutineScope()

    var flow by remember { mutableStateOf(CloudShareFlow.HOME) }
    var createStep by remember { mutableStateOf(CreateStep.WELCOME) }
    var joinStep by remember { mutableStateOf(JoinStep.WELCOME) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Choose what you want CloudShare to do.") }
    var checkRows by remember { mutableStateOf<List<CloudShareProvisioningStep>>(emptyList()) }

    var cloudflareToken by remember { mutableStateOf("") }
    var tokenVerified by remember { mutableStateOf(false) }
    var accountId by remember { mutableStateOf("") }
    var accountVerified by remember { mutableStateOf(false) }
    var workerName by remember { mutableStateOf("cts-cloudshare") }
    var d1Name by remember { mutableStateOf("cts-cloudshare-db") }
    var r2Name by remember { mutableStateOf("cts-cloudshare-backups") }
    var euResidency by remember { mutableStateOf(true) }
    var showAdvanced by remember { mutableStateOf(false) }
    var setupResult by remember { mutableStateOf<CloudShareProvisioningResult?>(null) }

    var workerUrl by remember { mutableStateOf(store.apiUrl) }
    var inviteCode by remember { mutableStateOf("") }
    var syncMinutes by remember { mutableStateOf(store.syncIntervalMinutes.toString()) }
    var backfillEnabled by remember { mutableStateOf(store.backfillEnabled) }

    var adminToken by remember { mutableStateOf(store.adminToken()) }
    var inviteLabel by remember { mutableStateOf("Android invitation") }
    var adminOutput by remember { mutableStateOf("") }
    var diagnosticsSnapshot by remember { mutableStateOf<CloudShareDiagnosticsSnapshot?>(null) }

    fun runAction(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (error: Exception) {
                status = "ERROR: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    fun resetCreate() {
        createStep = CreateStep.WELCOME
        cloudflareToken = ""
        tokenVerified = false
        accountVerified = false
        checkRows = emptyList()
        setupResult = null
        status = "Create a new CloudShare one step at a time."
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("CloudShare Setup Assistant", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "CloudShare is optional. Trading and local learning keep working even when Cloudflare is unavailable.",
            style = MaterialTheme.typography.bodySmall
        )

        when (flow) {
            CloudShareFlow.HOME -> {
                SetupStateCard(store)
                if (store.apiUrl.isNotBlank()) {
                    FilledTonalButton(
                        onClick = { flow = CloudShareFlow.REPAIR },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Test Current CloudShare") }
                }

                AssistantChoice(
                    number = "1",
                    title = "Set up my own CloudShare",
                    detail = "The assistant guides you through Cloudflare, verifies every prerequisite, then creates D1, R2 and the Worker automatically."
                ) {
                    resetCreate()
                    flow = CloudShareFlow.CREATE
                }
                AssistantChoice(
                    number = "2",
                    title = "Join an existing CloudShare",
                    detail = "Use a Worker URL and invitation code. The assistant tests the connection before registering this phone."
                ) {
                    joinStep = JoinStep.WELCOME
                    status = "Join an existing CloudShare one step at a time."
                    flow = CloudShareFlow.JOIN
                }
                AssistantChoice(
                    number = "3",
                    title = "Repair or reconnect",
                    detail = "Check Worker health, authentication, intelligence sync, backfill and device registration."
                ) { flow = CloudShareFlow.REPAIR }
                AssistantChoice(
                    number = "4",
                    title = "Manage my CloudShare",
                    detail = "Create invitations and inspect registered clients after owner setup is complete."
                ) { flow = CloudShareFlow.MANAGE }
            }

            CloudShareFlow.CREATE -> {
                when (createStep) {
                    CreateStep.WELCOME -> {
                        WizardProgress("Create your CloudShare", 1, 5)
                        InstructionCard(
                            "What the app will do",
                            listOf(
                                "Help you create a restricted one-time Cloudflare API token.",
                                "Verify the token before continuing.",
                                "Help you find your Cloudflare Account ID and verify all required permissions.",
                                "Create the D1 database and CloudShare tables.",
                                "Create the R2 backup bucket.",
                                "Deploy and bind the CloudShare Worker.",
                                "Register this phone and run the first sync automatically."
                            )
                        )
                        Text(
                            "You do not need to choose database, bucket or Worker names unless you open Advanced options later.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(
                            onClick = { createStep = CreateStep.TOKEN },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Start Setup") }
                        WizardBack { flow = CloudShareFlow.HOME }
                    }

                    CreateStep.TOKEN -> {
                        WizardProgress("Create your CloudShare", 2, 5)
                        Text("Create the temporary Cloudflare token", fontWeight = FontWeight.Bold)
                        InstructionCard(
                            "Do this in Cloudflare",
                            listOf(
                                "Tap Open Cloudflare API Tokens below.",
                                "Choose Create Token → Create Custom Token.",
                                "Give it a name such as CTS CloudShare Setup.",
                                "Add account permissions for D1 Write, Workers R2 Storage Write, and Workers Scripts Write.",
                                "Limit the token to the Cloudflare account you want to use.",
                                "Create the token and copy it. Cloudflare only shows the secret once.",
                                "Return here and paste it below."
                            )
                        )
                        OutlinedButton(
                            onClick = { uriHandler.openUri("https://dash.cloudflare.com/profile/api-tokens") },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Open Cloudflare API Tokens") }
                        OutlinedTextField(
                            value = cloudflareToken,
                            onValueChange = {
                                cloudflareToken = it
                                tokenVerified = false
                            },
                            label = { Text("Paste temporary API token") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                checkRows = emptyList()
                                runAction {
                                    val row = provisioner.verifyProvisioningToken(cloudflareToken)
                                    checkRows = listOf(row)
                                    tokenVerified = row.status == "PASS"
                                    if (tokenVerified) {
                                        status = "Token verified. Next, copy your Account ID."
                                        createStep = CreateStep.ACCOUNT
                                    }
                                }
                            },
                            enabled = !busy && cloudflareToken.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (busy) "Checking Token..." else "Verify Token & Continue") }
                        ProvisioningSteps(checkRows)
                        WizardBack { createStep = CreateStep.WELCOME }
                    }

                    CreateStep.ACCOUNT -> {
                        WizardProgress("Create your CloudShare", 3, 5)
                        Text("Copy your Cloudflare Account ID", fontWeight = FontWeight.Bold)
                        InstructionCard(
                            "Find the Account ID",
                            listOf(
                                "Tap Open Cloudflare Dashboard.",
                                "Open the account you want Crypto TradeStation to use.",
                                "Use the menu next to the account name and choose Copy Account ID.",
                                "Return here and paste the 32-character Account ID.",
                                "The app will test D1, R2 and Workers access before it lets setup continue."
                            )
                        )
                        OutlinedButton(
                            onClick = { uriHandler.openUri("https://dash.cloudflare.com/") },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Open Cloudflare Dashboard") }
                        OutlinedTextField(
                            value = accountId,
                            onValueChange = {
                                accountId = it.trim()
                                accountVerified = false
                            },
                            label = { Text("Cloudflare Account ID") },
                            supportingText = { Text("Expected: 32 hexadecimal characters") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                checkRows = emptyList()
                                runAction {
                                    val rows = provisioner.verifyProvisioningAccess(accountId, cloudflareToken)
                                    checkRows = rows
                                    accountVerified = rows.isNotEmpty() && rows.none { it.status == "FAIL" }
                                    if (accountVerified) {
                                        status = "Cloudflare account and permissions verified."
                                        createStep = CreateStep.REVIEW
                                    } else {
                                        status = "One or more Cloudflare permissions are missing. Fix the failed item and retry."
                                    }
                                }
                            },
                            enabled = !busy && tokenVerified && accountId.length == 32,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (busy) "Checking Permissions..." else "Verify Account & Continue") }
                        ProvisioningSteps(checkRows)
                        WizardBack { createStep = CreateStep.TOKEN }
                    }

                    CreateStep.REVIEW -> {
                        WizardProgress("Create your CloudShare", 4, 5)
                        Text("Review setup", fontWeight = FontWeight.Bold)
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                SummaryRow("Cloudflare account", accountId.take(8) + "…")
                                SummaryRow("Data location", if (euResidency) "EU jurisdiction" else "Cloudflare default")
                                SummaryRow("Automatic sync", "Every ${syncMinutes.toIntOrNull() ?: 5} minutes")
                                SummaryRow("Historical backfill", if (backfillEnabled) "Enabled" else "Disabled")
                                SummaryRow("Worker", workerName)
                                SummaryRow("D1", d1Name)
                                SummaryRow("R2", r2Name)
                            }
                        }
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Switch(checked = euResidency, onCheckedChange = { euResidency = it })
                            Spacer(Modifier.width(8.dp))
                            Text("Keep CloudShare D1/R2 in EU jurisdiction")
                        }
                        OutlinedTextField(
                            value = syncMinutes,
                            onValueChange = { syncMinutes = it.filter(Char::isDigit) },
                            label = { Text("Automatic sync interval (minutes)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Switch(checked = backfillEnabled, onCheckedChange = { backfillEnabled = it })
                            Spacer(Modifier.width(8.dp))
                            Text("Upload existing learning/history during backfill")
                        }
                        TextButton(onClick = { showAdvanced = !showAdvanced }) {
                            Text(if (showAdvanced) "Hide Advanced Resource Names" else "Advanced Resource Names")
                        }
                        if (showAdvanced) {
                            Text("You normally do not need to change these.", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(workerName, { workerName = it }, label = { Text("Worker name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            OutlinedTextField(d1Name, { d1Name = it }, label = { Text("D1 database name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            OutlinedTextField(r2Name, { r2Name = it }, label = { Text("R2 bucket name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        }
                        Button(
                            onClick = {
                                checkRows = emptyList()
                                setupResult = null
                                createStep = CreateStep.PROVISIONING
                                runAction {
                                    val result = provisioner.provision(
                                        accountId = accountId,
                                        apiToken = cloudflareToken,
                                        workerName = workerName,
                                        d1Name = d1Name,
                                        r2BucketName = r2Name,
                                        euResidency = euResidency,
                                        backfillEnabled = backfillEnabled,
                                        syncIntervalMinutes = syncMinutes.toIntOrNull() ?: 5,
                                        onProgress = { row -> checkRows = checkRows + row }
                                    )
                                    setupResult = result
                                    cloudflareToken = ""
                                    tokenVerified = false
                                    if (result.success) {
                                        workerUrl = result.workerUrl
                                        status = "CloudShare was created and verified successfully."
                                        createStep = CreateStep.COMPLETE
                                    } else {
                                        status = "Setup stopped at the failed step. Correct it and retry."
                                    }
                                }
                            },
                            enabled = !busy && accountVerified,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Create My CloudShare") }
                        WizardBack { createStep = CreateStep.ACCOUNT }
                    }

                    CreateStep.PROVISIONING -> {
                        WizardProgress("Create your CloudShare", 5, 5)
                        Text("Automatic setup in progress", fontWeight = FontWeight.Bold)
                        Text(
                            "You can stay on this screen. Crypto TradeStation is creating and testing each CloudShare component in order.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        ProvisioningSteps(checkRows)
                        val failed = setupResult?.success == false
                        if (failed) {
                            Button(
                                onClick = { createStep = CreateStep.REVIEW },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Review & Retry") }
                        }
                    }

                    CreateStep.COMPLETE -> {
                        WizardProgress("Create your CloudShare", 5, 5)
                        Text("CloudShare is ready", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        SetupStateCard(store)
                        setupResult?.let { result ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("Created successfully", fontWeight = FontWeight.Bold)
                                    Text("Worker: ${result.workerUrl}", style = MaterialTheme.typography.bodySmall)
                                    Text("D1 database: ${result.d1DatabaseId.take(10)}…", style = MaterialTheme.typography.bodySmall)
                                    Text("R2 bucket: ${result.r2BucketName}", style = MaterialTheme.typography.bodySmall)
                                    Text("This phone: Registered", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                checkRows = emptyList()
                                runAction {
                                    checkRows = provisioner.verifyExisting(store.apiUrl)
                                    status = if (checkRows.any { it.status == "FAIL" }) "Final test found a problem." else "Final CloudShare test passed."
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Run Final CloudShare Test") }
                        ProvisioningSteps(checkRows)
                        Button(
                            onClick = { flow = CloudShareFlow.HOME },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Finish") }
                    }
                }
            }

            CloudShareFlow.JOIN -> {
                when (joinStep) {
                    JoinStep.WELCOME -> {
                        WizardProgress("Join an existing CloudShare", 1, 3)
                        InstructionCard(
                            "You need two things",
                            listOf(
                                "The CloudShare Worker HTTPS URL from the owner.",
                                "A valid invitation code created by that CloudShare owner."
                            )
                        )
                        Button(onClick = { joinStep = JoinStep.WORKER }, modifier = Modifier.fillMaxWidth()) { Text("Start") }
                        WizardBack { flow = CloudShareFlow.HOME }
                    }
                    JoinStep.WORKER -> {
                        WizardProgress("Join an existing CloudShare", 2, 3)
                        Text("Test the Worker first", fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            workerUrl, { workerUrl = it.trim() },
                            label = { Text("CloudShare Worker HTTPS URL") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        Button(
                            onClick = {
                                runAction {
                                    store.apiUrl = workerUrl
                                    val health = engine.health()
                                    status = "Worker health passed: $health"
                                    joinStep = JoinStep.INVITE
                                }
                            },
                            enabled = !busy && workerUrl.startsWith("https://"),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Test Worker & Continue") }
                        WizardBack { joinStep = JoinStep.WELCOME }
                    }
                    JoinStep.INVITE -> {
                        WizardProgress("Join an existing CloudShare", 3, 3)
                        Text("Register this phone", fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            inviteCode, { inviteCode = it },
                            label = { Text("Invitation code") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        Button(
                            onClick = {
                                runAction {
                                    store.apiUrl = workerUrl
                                    val credentials = engine.register(inviteCode)
                                    inviteCode = ""
                                    val client = CloudShareClient(store.apiUrl, credentials = credentials)
                                    client.clientStatus()
                                    client.intelligenceStatus()
                                    status = "Device registration passed. Choose sync options."
                                    joinStep = JoinStep.OPTIONS
                                }
                            },
                            enabled = !busy && inviteCode.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Register & Verify") }
                        WizardBack { joinStep = JoinStep.WORKER }
                    }
                    JoinStep.OPTIONS -> {
                        WizardProgress("Join an existing CloudShare", 3, 3)
                        Text("Finish synchronization setup", fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            syncMinutes,
                            { syncMinutes = it.filter(Char::isDigit) },
                            label = { Text("Automatic sync interval (minutes)") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Switch(checked = backfillEnabled, onCheckedChange = { backfillEnabled = it })
                            Spacer(Modifier.width(8.dp))
                            Text("Backfill existing learning/history")
                        }
                        Button(
                            onClick = {
                                runAction {
                                    store.syncIntervalMinutes = syncMinutes.toIntOrNull() ?: 5
                                    store.backfillEnabled = backfillEnabled
                                    store.enabled = true
                                    val result = engine.syncIfDue(force = true)
                                    require(result.error.isBlank()) { result.error }
                                    status = "CloudShare joined. uploaded=${result.uploaded}, downloaded=${result.downloaded}, backfill=${result.backfilled}"
                                    joinStep = JoinStep.COMPLETE
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Run Initial Sync") }
                    }
                    JoinStep.COMPLETE -> {
                        Text("CloudShare joined successfully", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        SetupStateCard(store)
                        Button(onClick = { flow = CloudShareFlow.HOME }, modifier = Modifier.fillMaxWidth()) { Text("Finish") }
                    }
                }
            }

            CloudShareFlow.REPAIR -> {
                Text("Repair & Test CloudShare", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                SetupStateCard(store)
                OutlinedButton(
                    onClick = {
                        runAction {
                            diagnosticsSnapshot = engine.diagnostics()
                            val snapshot = diagnosticsSnapshot!!
                            status = "Data=${snapshot.dataState}; indexed=${snapshot.indexedEvidenceSamples}; resolved outcomes=${snapshot.outcomeSamples}/${store.collectiveMinSamples}."
                        }
                    },
                    enabled = !busy && store.enabled,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Inspect Learning Readiness") }
                diagnosticsSnapshot?.let { CloudShareReadinessCard(it, store.collectiveMinSamples) }
                Button(
                    onClick = {
                        checkRows = emptyList()
                        runAction {
                            checkRows = provisioner.verifyExisting(store.apiUrl)
                            status = if (checkRows.any { it.status == "FAIL" }) "CloudShare needs attention." else "CloudShare verification passed."
                        }
                    },
                    enabled = !busy && store.apiUrl.startsWith("https://"),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Run Full CloudShare Test") }
                OutlinedButton(
                    onClick = {
                        runAction {
                            val result = engine.syncIfDue(force = true)
                            if (result.error.isBlank()) diagnosticsSnapshot = engine.diagnostics()
                            status = "Sync: uploaded=${result.uploaded}, downloaded=${result.downloaded}, backfill=${result.backfilled}" +
                                if (result.error.isBlank()) "" else ", error=${result.error}"
                        }
                    },
                    enabled = !busy && store.enabled,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Force Sync Now") }
                OutlinedButton(
                    onClick = {
                        runAction { status = "Bootstrap upload: ${engine.createAndUploadBootstrap()}" }
                    },
                    enabled = !busy && store.credentials() != null,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Upload Full Bootstrap") }
                ProvisioningSteps(checkRows)
                WizardBack { flow = CloudShareFlow.HOME }
            }

            CloudShareFlow.MANAGE -> {
                Text("Manage CloudShare", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Owner controls are only needed after CloudShare has been created.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    adminToken, { adminToken = it },
                    label = { Text("CloudShare owner/admin token") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Button(
                    onClick = {
                        store.saveAdminToken(adminToken)
                        runAction {
                            adminOutput = engine.adminPing().toString()
                            status = "Owner authentication passed."
                        }
                    },
                    enabled = !busy && adminToken.isNotBlank() && store.apiUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Verify Owner") }
                OutlinedTextField(
                    inviteLabel, { inviteLabel = it },
                    label = { Text("New invitation label") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                OutlinedButton(
                    onClick = {
                        store.saveAdminToken(adminToken)
                        runAction {
                            adminOutput = engine.adminCreateInvite(inviteLabel).toString()
                            status = "Invitation created. Copy invite_code from the result."
                        }
                    },
                    enabled = !busy && adminToken.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Create Invitation") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            store.saveAdminToken(adminToken)
                            runAction { adminOutput = engine.adminInvites().toString() }
                        },
                        enabled = !busy && adminToken.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("Invites") }
                    OutlinedButton(
                        onClick = {
                            store.saveAdminToken(adminToken)
                            runAction { adminOutput = engine.adminClients().toString() }
                        },
                        enabled = !busy && adminToken.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("Clients") }
                }
                if (adminOutput.isNotBlank()) {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Text(adminOutput, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                WizardBack { flow = CloudShareFlow.HOME }
            }
        }

        HorizontalDivider()
        Text("Assistant Status", fontWeight = FontWeight.Bold)
        Text(status, style = MaterialTheme.typography.bodySmall)
        if (busy && createStep != CreateStep.PROVISIONING) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun CloudShareReadinessCard(snapshot: CloudShareDiagnosticsSnapshot, requiredOutcomes: Int) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Learning Readiness", fontWeight = FontWeight.Bold)
            Text("Data readiness: ${snapshot.dataState}", style = MaterialTheme.typography.bodySmall)
            Text("Indexed evidence: ${snapshot.indexedEvidenceSamples} samples / ${snapshot.indexedEvidenceRows} rows", style = MaterialTheme.typography.bodySmall)
            Text("Observational samples: ${snapshot.observationSamples}", style = MaterialTheme.typography.bodySmall)
            Text("Resolved outcome samples: ${snapshot.outcomeSamples}/$requiredOutcomes", style = MaterialTheme.typography.bodySmall)
            Text("Outcome learning: ${snapshot.outcomeState}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Signals and decisions can make data READY, but they never count as profit/win-rate outcomes. Collective score adjustment stays neutral until enough matching resolved outcomes exist.",
                style = MaterialTheme.typography.labelSmall
            )
            if (snapshot.newestDataTimestamp.isNotBlank()) {
                Text("Newest data: ${snapshot.newestDataTimestamp}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun WizardProgress(title: String, step: Int, total: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Step $step of $total", style = MaterialTheme.typography.labelMedium)
        LinearProgressIndicator(
            progress = step.toFloat() / total.toFloat(),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun InstructionCard(title: String, lines: List<String>) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            lines.forEachIndexed { index, line ->
                Text("${index + 1}. $line", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SetupStateCard(store: CloudShareSettingsStore) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Current CloudShare", fontWeight = FontWeight.Bold)
            Text("Worker: ${store.apiUrl.ifBlank { "Not configured" }}", style = MaterialTheme.typography.bodySmall)
            Text("This phone: ${if (store.credentials() != null) "Registered" else "Not registered"}", style = MaterialTheme.typography.bodySmall)
            Text("Automatic sync: ${if (store.enabled) "Enabled" else "Disabled"}", style = MaterialTheme.typography.bodySmall)
            Text("Interval: ${store.syncIntervalMinutes} minutes", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AssistantChoice(number: String, title: String, detail: String, onClick: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$number. $title", fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
        }
    }
}

@Composable
private fun SummaryRow(name: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WizardBack(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text("Back") }
}

@Composable
private fun ProvisioningSteps(rows: List<CloudShareProvisioningStep>) {
    if (rows.isEmpty()) return
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Verification / setup progress", fontWeight = FontWeight.Bold)
            rows.takeLast(40).forEach { row ->
                val color = when (row.status) {
                    "PASS" -> MaterialTheme.colorScheme.secondary
                    "FAIL" -> MaterialTheme.colorScheme.error
                    "WARN" -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                }
                Text(
                    "${row.status} • ${row.name}",
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(row.detail, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
