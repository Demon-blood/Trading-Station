#!/usr/bin/env python3
from __future__ import annotations

import os
import sys
from pathlib import Path

NEW_ROUTER = "app/src/main/java/com/ksp/cryptobot/intelligence/OpenAiDecisionRouter.kt"
NEW_TEST = "app/src/test/java/com/ksp/cryptobot/intelligence/OpenAiDecisionRouterTest.kt"

def fail(message: str):
    raise SystemExit("ERROR | " + message)

def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        fail(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)

def main():
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    if not (repo / ".git").exists():
        fail("Not a git checkout")

    dirty = os.popen(f'cd "{repo}" && git status --porcelain -- app').read().strip()
    if dirty:
        fail("Refusing to patch dirty app/ tree:\n" + dirty)

    payload_root = Path(__file__).resolve().parent / "m6_payload"
    for rel in (NEW_ROUTER, NEW_TEST):
        source = payload_root / rel
        target = repo / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source.read_text(encoding="utf-8").rstrip() + "\n", encoding="utf-8")
        print("WRITE |", rel)

    # ------------------------------------------------------------------
    # AppSettingsStore: encrypted OpenAI key + cheap bounded cloud config.
    # ------------------------------------------------------------------
    settings_path = repo / "app/src/main/java/com/ksp/cryptobot/settings/AppSettingsStore.kt"
    settings = settings_path.read_text(encoding="utf-8")

    settings = replace_once(
        settings,
        '''data class SettingsSaveVerification(
    val committed: Boolean,
    val exactMatch: Boolean,
    val timestampEpochMs: Long,
    val effectiveMode: String
)
''',
        '''data class SettingsSaveVerification(
    val committed: Boolean,
    val exactMatch: Boolean,
    val timestampEpochMs: Long,
    val effectiveMode: String
)

data class CloudAiConfig(
    val enabled: Boolean = false,
    val monthlyBudgetUsd: BigDecimal = BigDecimal("2.00"),
    val solEnabled: Boolean = true,
    val maxSolCallsPerDay: Int = 3
)
''',
        "Cloud AI settings model"
    )

    settings = replace_once(
        settings,
        '''    fun saveBinanceKeys(apiKey: String, secretKey: String) {
''',
        '''    fun saveOpenAiApiKey(apiKey: String) {
        secure.saveEncryptedString("openai_api_key", apiKey.trim())
    }

    fun openAiApiKey(): String? =
        secure.readEncryptedString("openai_api_key")?.takeIf { it.isNotBlank() }

    fun cloudAiConfig(): CloudAiConfig = CloudAiConfig(
        enabled = prefs.getBoolean("cloud_ai_enabled", false),
        monthlyBudgetUsd = prefs.getString("cloud_ai_monthly_budget_usd", "2.00")
            ?.toBigDecimalOrNull()
            ?.coerceIn(BigDecimal.ZERO, BigDecimal("100.00"))
            ?: BigDecimal("2.00"),
        solEnabled = prefs.getBoolean("cloud_ai_sol_enabled", true),
        maxSolCallsPerDay = prefs.getInt("cloud_ai_max_sol_calls_per_day", 3).coerceIn(0, 50)
    )

    fun saveCloudAiConfig(
        enabled: Boolean,
        monthlyBudgetUsd: BigDecimal,
        solEnabled: Boolean,
        maxSolCallsPerDay: Int
    ) {
        prefs.edit()
            .putBoolean("cloud_ai_enabled", enabled)
            .putString(
                "cloud_ai_monthly_budget_usd",
                monthlyBudgetUsd.coerceIn(BigDecimal.ZERO, BigDecimal("100.00")).toPlainString()
            )
            .putBoolean("cloud_ai_sol_enabled", solEnabled)
            .putInt("cloud_ai_max_sol_calls_per_day", maxSolCallsPerDay.coerceIn(0, 50))
            .apply()
    }

    fun saveBinanceKeys(apiKey: String, secretKey: String) {
''',
        "Cloud AI secure/config methods"
    )

    settings = replace_once(
        settings,
        '''        guardianApiKey()?.let { out["guardian_api_key"] = it }
        telegramBotToken()?.let { out["telegram_bot_token"] = it }
''',
        '''        guardianApiKey()?.let { out["guardian_api_key"] = it }
        openAiApiKey()?.let { out["openai_api_key"] = it }
        telegramBotToken()?.let { out["telegram_bot_token"] = it }
''',
        "OpenAI secure backup"
    )

    settings = replace_once(
        settings,
        '''        values["guardian_api_key"]?.let { saveGuardianApiKey(it) }
        if (values.containsKey("telegram_bot_token") || values.containsKey("telegram_chat_id")) {
''',
        '''        values["guardian_api_key"]?.let { saveGuardianApiKey(it) }
        values["openai_api_key"]?.let { saveOpenAiApiKey(it) }
        if (values.containsKey("telegram_bot_token") || values.containsKey("telegram_chat_id")) {
''',
        "OpenAI secure restore"
    )

    # AppSettingsStore does not currently define a BigDecimal coerceIn helper.
    settings = replace_once(
        settings,
        '''    fun guardianApiKey(): String? = secure.readEncryptedString("guardian_api_key")?.takeIf { it.isNotBlank() }
}
''',
        '''    fun guardianApiKey(): String? = secure.readEncryptedString("guardian_api_key")?.takeIf { it.isNotBlank() }

    private fun BigDecimal.coerceIn(lo: BigDecimal, hi: BigDecimal): BigDecimal = when {
        this < lo -> lo
        this > hi -> hi
        else -> this
    }
}
''',
        "Settings BigDecimal bound helper"
    )

    settings_path.write_text(settings, encoding="utf-8")
    print("PATCH |", settings_path.relative_to(repo))

    # ---------------------------------------------------------------
    # MainActivity settings UI: cloud AI remains explicit opt-in.
    # ---------------------------------------------------------------
    main_path = repo / "app/src/main/java/com/ksp/cryptobot/MainActivity.kt"
    main = main_path.read_text(encoding="utf-8")

    main = replace_once(
        main,
        '''    var gNewsKey by remember { mutableStateOf("") }
    var guardianKey by remember { mutableStateOf("") }
    var symbols by remember { mutableStateOf(settings.symbolsCsv) }
''',
        '''    var gNewsKey by remember { mutableStateOf("") }
    var guardianKey by remember { mutableStateOf("") }
    val initialCloudAiConfig = remember { store.cloudAiConfig() }
    var openAiKey by remember { mutableStateOf("") }
    var openAiConfigured by remember { mutableStateOf(store.openAiApiKey() != null) }
    var cloudAiEnabled by remember { mutableStateOf(initialCloudAiConfig.enabled) }
    var cloudAiMonthlyBudget by remember { mutableStateOf(initialCloudAiConfig.monthlyBudgetUsd.toPlainString()) }
    var cloudAiSolEnabled by remember { mutableStateOf(initialCloudAiConfig.solEnabled) }
    var cloudAiMaxSolCalls by remember { mutableStateOf(initialCloudAiConfig.maxSolCallsPerDay.toString()) }
    var symbols by remember { mutableStateOf(settings.symbolsCsv) }
''',
        "Cloud AI Compose state"
    )

    main = replace_once(
        main,
        '''                    gNewsKey = gNewsKey,
                    guardianKey = guardianKey,
                    onApiKey = { apiKey = it },
''',
        '''                    gNewsKey = gNewsKey,
                    guardianKey = guardianKey,
                    openAiKey = openAiKey,
                    openAiConfigured = openAiConfigured,
                    cloudAiEnabled = cloudAiEnabled,
                    cloudAiMonthlyBudget = cloudAiMonthlyBudget,
                    cloudAiSolEnabled = cloudAiSolEnabled,
                    cloudAiMaxSolCalls = cloudAiMaxSolCalls,
                    onApiKey = { apiKey = it },
''',
        "SettingsScreen cloud value arguments"
    )

    main = replace_once(
        main,
        '''                    onGNewsKey = { gNewsKey = it },
                    onGuardianKey = { guardianKey = it },
                    settings = settings,
''',
        '''                    onGNewsKey = { gNewsKey = it },
                    onGuardianKey = { guardianKey = it },
                    onOpenAiKey = { openAiKey = it },
                    onCloudAiEnabled = { cloudAiEnabled = it },
                    onCloudAiMonthlyBudget = { cloudAiMonthlyBudget = it },
                    onCloudAiSolEnabled = { cloudAiSolEnabled = it },
                    onCloudAiMaxSolCalls = { cloudAiMaxSolCalls = it },
                    settings = settings,
''',
        "SettingsScreen cloud callbacks"
    )

    main = replace_once(
        main,
        '''                        if (gNewsKey.isNotBlank()) store.saveGNewsApiKey(gNewsKey)
                        if (guardianKey.isNotBlank()) store.saveGuardianApiKey(guardianKey)
                        status = "${settings.exchangeProvider.name.replace('_', ' ')} / news secrets saved locally"
''',
        '''                        if (gNewsKey.isNotBlank()) store.saveGNewsApiKey(gNewsKey)
                        if (guardianKey.isNotBlank()) store.saveGuardianApiKey(guardianKey)
                        if (openAiKey.isNotBlank()) {
                            store.saveOpenAiApiKey(openAiKey)
                            openAiConfigured = true
                            openAiKey = ""
                        }
                        store.saveCloudAiConfig(
                            enabled = cloudAiEnabled,
                            monthlyBudgetUsd = cloudAiMonthlyBudget.toBigDecimalOrNull() ?: BigDecimal("2.00"),
                            solEnabled = cloudAiSolEnabled,
                            maxSolCallsPerDay = cloudAiMaxSolCalls.toIntOrNull() ?: 3
                        )
                        status = "${settings.exchangeProvider.name.replace('_', ' ')} / news / selective AI secrets saved locally"
''',
        "Save cloud AI credentials/config"
    )

    main = replace_once(
        main,
        '''    gNewsKey: String,
    guardianKey: String,
    onApiKey: (String) -> Unit,
''',
        '''    gNewsKey: String,
    guardianKey: String,
    openAiKey: String,
    openAiConfigured: Boolean,
    cloudAiEnabled: Boolean,
    cloudAiMonthlyBudget: String,
    cloudAiSolEnabled: Boolean,
    cloudAiMaxSolCalls: String,
    onApiKey: (String) -> Unit,
''',
        "SettingsScreen cloud parameters"
    )

    main = replace_once(
        main,
        '''    onGNewsKey: (String) -> Unit,
    onGuardianKey: (String) -> Unit,
    settings: BotSettings,
''',
        '''    onGNewsKey: (String) -> Unit,
    onGuardianKey: (String) -> Unit,
    onOpenAiKey: (String) -> Unit,
    onCloudAiEnabled: (Boolean) -> Unit,
    onCloudAiMonthlyBudget: (String) -> Unit,
    onCloudAiSolEnabled: (Boolean) -> Unit,
    onCloudAiMaxSolCalls: (String) -> Unit,
    settings: BotSettings,
''',
        "SettingsScreen cloud callback parameters"
    )

    main = replace_once(
        main,
        '''                OutlinedTextField(value = guardianKey, onValueChange = onGuardianKey, label = { Text("Guardian API key") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
                Text("News stack: GDELT + RSS + CryptoPanic + Marketaux + NewsData.io + GNews + Guardian + NewsAPI.org. GDELT/RSS need no API key.", color = Muted)
                Button(onClick = onSaveKeys, modifier = Modifier.fillMaxWidth()) { Text("Save Secure Keys") }
''',
        '''                OutlinedTextField(value = guardianKey, onValueChange = onGuardianKey, label = { Text("Guardian API key") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
                Text("News stack: GDELT + RSS + CryptoPanic + Marketaux + NewsData.io + GNews + Guardian + NewsAPI.org. GDELT/RSS need no API key.", color = Muted)

                Spacer(Modifier.height(8.dp))
                Text("Selective Cloud AI Validation", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = openAiKey,
                    onValueChange = onOpenAiKey,
                    label = { Text(if (openAiConfigured) "OpenAI API key (configured; enter only to replace)" else "OpenAI API key") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                ToggleRow("Enable selective cloud AI", cloudAiEnabled, onCloudAiEnabled)
                OutlinedTextField(
                    value = cloudAiMonthlyBudget,
                    onValueChange = onCloudAiMonthlyBudget,
                    label = { Text("Monthly OpenAI API budget (USD)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                ToggleRow("Allow rare GPT-5.6 Sol escalation", cloudAiSolEnabled, onCloudAiSolEnabled)
                OutlinedTextField(
                    value = cloudAiMaxSolCalls,
                    onValueChange = onCloudAiMaxSolCalls,
                    label = { Text("Maximum Sol calls per UTC day") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text(
                    "Deterministic/local logic remains primary. Luna validates selected BUY candidates; Sol is rare escalation. Cloud AI may veto or reduce size but cannot create or enlarge a trade. M5 net-EV and deterministic risk remain final authority.",
                    color = Muted
                )
                Button(onClick = onSaveKeys, modifier = Modifier.fillMaxWidth()) { Text("Save Secure Keys") }
''',
        "Cloud AI settings UI"
    )

    main_path.write_text(main, encoding="utf-8")
    print("PATCH |", main_path.relative_to(repo))

    # ---------------------------------------------------------------
    # BotController: route after all deterministic intelligence.
    # ---------------------------------------------------------------
    controller_path = repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt"
    controller = controller_path.read_text(encoding="utf-8")

    controller = replace_once(
        controller,
        "import com.ksp.cryptobot.intelligence.AiDecisionEngine\n",
        "import com.ksp.cryptobot.intelligence.AiDecisionEngine\nimport com.ksp.cryptobot.intelligence.OpenAiDecisionRouter\n",
        "BotController cloud AI import"
    )

    controller = replace_once(
        controller,
        '''    private val advancedExecution = AdvancedExecutionCoordinator(dao, AppDatabase.get(appContext).governanceDao())
    private val protectiveStops = ProtectiveStopManager(dao, AppDatabase.get(appContext).governanceDao())
''',
        '''    private val advancedExecution = AdvancedExecutionCoordinator(dao, AppDatabase.get(appContext).governanceDao())
    private val protectiveStops = ProtectiveStopManager(dao, AppDatabase.get(appContext).governanceDao())
    private val cloudAiRouter = OpenAiDecisionRouter(appContext, settingsStore)
''',
        "BotController cloud AI router property"
    )

    controller = replace_once(
        controller,
        '''                val decision = productionResult.first
                val production = productionResult.second
                updateStatus("[$symbol] Production intelligence: blocked=${production.blocked}, adj=${production.scoreAdjustment}, size×${"%.2f".format(production.sizeMultiplier)}, safe=${production.safeMode.level}, anomaly=${production.anomaly.severity}, kill=${production.killSwitch.severity}. ${production.reason.take(240)}", if (production.blocked) "WARN" else "INFO")
''',
        '''                val deterministicDecision = productionResult.first
                val production = productionResult.second
                updateStatus("[$symbol] Production intelligence: blocked=${production.blocked}, adj=${production.scoreAdjustment}, size×${"%.2f".format(production.sizeMultiplier)}, safe=${production.safeMode.level}, anomaly=${production.anomaly.severity}, kill=${production.killSwitch.severity}. ${production.reason.take(240)}", if (production.blocked) "WARN" else "INFO")

                val cloudAi = cloudAiRouter.reviewIfUseful(
                    decision = deterministicDecision,
                    ticker = ticker,
                    settings = settings,
                    strategy = research.selectedStrategy.toString(),
                    regime = research.regime.regime.toString(),
                    news = news,
                    recentTrades = recentTrades
                )
                val decision = cloudAi.decision
                if (cloudAi.review.modelPath != "DETERMINISTIC" || cloudAi.review.verdict == com.ksp.cryptobot.intelligence.CloudAiVerdict.REJECT) {
                    val budget = cloudAiRouter.budgetSnapshot()
                    updateStatus(
                        "[$symbol] Selective cloud AI: ${cloudAi.review.verdict} via ${cloudAi.review.modelPath}, risk×${cloudAi.review.riskMultiplier.setScale(2, RoundingMode.HALF_UP)}, callCost≈${cloudAi.review.totalCostUsd.setScale(6, RoundingMode.HALF_UP)} USD, month=${budget.spentUsd.setScale(4, RoundingMode.HALF_UP)}/${budget.monthlyBudgetUsd.setScale(2, RoundingMode.HALF_UP)} USD. ${cloudAi.review.reason.take(220)}",
                        if (cloudAi.review.verdict == com.ksp.cryptobot.intelligence.CloudAiVerdict.REJECT) "WARN" else "INFO"
                    )
                }
''',
        "BotController selective cloud validation"
    )

    controller = replace_once(
        controller,
        '''        add("PASS", "Secure Exchange Key Store", "Encrypted key store is reachable. Keys are not exposed in diagnostics.")

        try {
''',
        '''        add("PASS", "Secure Exchange Key Store", "Encrypted key store is reachable. Keys are not exposed in diagnostics.")

        val cloudConfig = settingsStore.cloudAiConfig()
        val cloudKeyConfigured = !settingsStore.openAiApiKey().isNullOrBlank()
        when {
            !cloudConfig.enabled ->
                add("PASS", "Selective Cloud AI Router", "Disabled. Deterministic/local zero-API-cost path is active.")
            !cloudKeyConfigured ->
                add("WARN", "Selective Cloud AI Router", "Enabled but OpenAI API key is not configured. Trading continues on deterministic safeguards.")
            else -> {
                val cloudBudget = cloudAiRouter.budgetSnapshot()
                add(
                    "PASS",
                    "Selective Cloud AI Router",
                    "Enabled; key stored securely; budget=${cloudBudget.spentUsd.setScale(4, RoundingMode.HALF_UP)}/${cloudBudget.monthlyBudgetUsd.setScale(2, RoundingMode.HALF_UP)} USD; Sol=${cloudConfig.solEnabled}; SolToday=${cloudBudget.solCallsToday}/${cloudConfig.maxSolCallsPerDay}. This check makes no paid API call."
                )
            }
        }

        try {
''',
        "Cloud AI zero-cost system verification"
    )

    controller_path.write_text(controller, encoding="utf-8")
    print("PATCH |", controller_path.relative_to(repo))

    # -----------------------------------------------------------------
    # Advanced execution: AI may only reduce size; actual call cost goes
    # into M5 EV before execution.
    # -----------------------------------------------------------------
    advanced_path = repo / "app/src/main/java/com/ksp/cryptobot/execution/AdvancedExecutionCoordinator.kt"
    advanced = advanced_path.read_text(encoding="utf-8")

    advanced = replace_once(
        advanced,
        "import com.ksp.cryptobot.governance.ProductionIntelligenceRuntime\n",
        "import com.ksp.cryptobot.governance.ProductionIntelligenceRuntime\nimport com.ksp.cryptobot.intelligence.CloudAiRuntime\n",
        "Advanced execution CloudAiRuntime import"
    )

    advanced = replace_once(
        advanced,
        '''        var finalQuote = liquidity.finalQuote
        // Economic minimum from desktop v1.0.50, made fail-safe for Android:
''',
        '''        var finalQuote = liquidity.finalQuote
        val cloudReview = CloudAiRuntime.snapshotFor(decision)
        if (cloudReview != null) {
            val cloudMultiplier = cloudReview.riskMultiplier.coerceIn(BigDecimal.ZERO, BigDecimal.ONE)
            val beforeCloud = finalQuote
            finalQuote = finalQuote.multiply(cloudMultiplier).setScale(2, RoundingMode.DOWN)
            if (cloudMultiplier < BigDecimal.ONE || cloudReview.totalCostQuote > BigDecimal.ZERO) {
                record(
                    "cloud_ai_cap",
                    decision.symbol,
                    settings,
                    mode,
                    beforeCloud,
                    finalQuote,
                    cloudMultiplier,
                    "",
                    cloudReview.modelPath,
                    sizeBand(beforeCloud),
                    "",
                    if (finalQuote < beforeCloud) "reduced" else "normal",
                    false,
                    "Selective cloud AI ${cloudReview.verdict}; risk×${cloudMultiplier}; API cost reserve=${cloudReview.totalCostQuote}; ${cloudReview.reason}",
                    if (finalQuote < beforeCloud) "WARN" else "INFO"
                )
            }
        }
        // Economic minimum from desktop v1.0.50, made fail-safe for Android:
''',
        "Cloud AI veto/reduce-only sizing"
    )

    advanced = replace_once(
        advanced,
        '''                externalDecisionCostQuote = BigDecimal.ZERO,
                safetyMarginRate = BigDecimal("0.0025")
''',
        '''                externalDecisionCostQuote = cloudReview?.totalCostQuote ?: BigDecimal.ZERO,
                safetyMarginRate = BigDecimal("0.0025")
''',
        "Charge AI cost into M5 EV"
    )

    advanced_path.write_text(advanced, encoding="utf-8")
    print("PATCH |", advanced_path.relative_to(repo))

    # Controlled scope.
    changed = set(os.popen(f'cd "{repo}" && git diff --name-only -- app').read().splitlines())
    untracked = set(os.popen(f'cd "{repo}" && git ls-files --others --exclude-standard -- app').read().splitlines())
    all_changed = changed | untracked
    allowed = {
        NEW_ROUTER,
        NEW_TEST,
        "app/src/main/java/com/ksp/cryptobot/settings/AppSettingsStore.kt",
        "app/src/main/java/com/ksp/cryptobot/MainActivity.kt",
        "app/src/main/java/com/ksp/cryptobot/core/BotController.kt",
        "app/src/main/java/com/ksp/cryptobot/execution/AdvancedExecutionCoordinator.kt",
    }
    unexpected = sorted(all_changed - allowed)
    missing = sorted(allowed - all_changed)
    if unexpected:
        fail("Unexpected M6 app changes: " + ", ".join(unexpected))
    if missing:
        fail("Expected M6 app changes missing: " + ", ".join(missing))

    print("PASS | M6 patch changed only approved AI/settings/integration files.")

if __name__ == "__main__":
    main()
