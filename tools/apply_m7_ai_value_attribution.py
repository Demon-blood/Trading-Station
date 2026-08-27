#!/usr/bin/env python3
from __future__ import annotations

import os
import sys
from pathlib import Path

NEW_ENTITY = "app/src/main/java/com/ksp/cryptobot/data/AiValueAttributionEntities.kt"
NEW_ENGINE = "app/src/main/java/com/ksp/cryptobot/intelligence/AiValueAttributionEngine.kt"
NEW_TEST = "app/src/test/java/com/ksp/cryptobot/intelligence/AiValueAttributionEngineTest.kt"

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

    payload_root = Path(__file__).resolve().parent / "m7_payload"
    for rel in (NEW_ENTITY, NEW_ENGINE, NEW_TEST):
        source = payload_root / rel
        target = repo / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source.read_text(encoding="utf-8").rstrip() + "\n", encoding="utf-8")
        print("WRITE |", rel)

    dao_path = repo / "app/src/main/java/com/ksp/cryptobot/data/GovernanceDao.kt"
    dao = dao_path.read_text(encoding="utf-8")

    dao = replace_once(
        dao,
        '''    @Insert suspend fun insertAdvancedExecution(event: AdvancedExecutionEventEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putState(state: ProductionIntelligenceStateEntity)
''',
        '''    @Insert suspend fun insertAdvancedExecution(event: AdvancedExecutionEventEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putState(state: ProductionIntelligenceStateEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAiAttribution(row: AiValueAttributionEntity)
''',
        "GovernanceDao attribution insert"
    )

    dao = replace_once(
        dao,
        '''    @Query("SELECT * FROM advanced_execution_events WHERE eventType=:eventType ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun advancedExecutionByType(eventType: String, limit: Int = 500): List<AdvancedExecutionEventEntity>
    @Query("SELECT * FROM production_intelligence_state ORDER BY updatedAtEpochMs ASC")
''',
        '''    @Query("SELECT * FROM advanced_execution_events WHERE eventType=:eventType ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun advancedExecutionByType(eventType: String, limit: Int = 500): List<AdvancedExecutionEventEntity>

    @Query("SELECT * FROM ai_value_attribution WHERE fingerprint=:fingerprint LIMIT 1")
    suspend fun aiAttributionByFingerprint(fingerprint: String): AiValueAttributionEntity?

    @Query("SELECT * FROM ai_value_attribution WHERE status='OPEN' AND symbol=:symbol ORDER BY createdAtEpochMs ASC")
    suspend fun openAiAttributionForSymbol(symbol: String): List<AiValueAttributionEntity>

    @Query("SELECT COUNT(*) FROM ai_value_attribution WHERE status='OPEN'")
    suspend fun openAiAttributionCount(): Int

    @Query("SELECT * FROM ai_value_attribution WHERE status='RESOLVED' ORDER BY resolvedAtEpochMs DESC LIMIT :limit")
    suspend fun resolvedAiAttributions(limit: Int = 5000): List<AiValueAttributionEntity>

    @Query("SELECT * FROM ai_value_attribution ORDER BY createdAtEpochMs DESC LIMIT :limit")
    suspend fun recentAiAttributions(limit: Int = 200): List<AiValueAttributionEntity>

    @Query("SELECT * FROM production_intelligence_state ORDER BY updatedAtEpochMs ASC")
''',
        "GovernanceDao attribution queries"
    )

    dao = replace_once(
        dao,
        '''    @Query("DELETE FROM advanced_execution_events")
    suspend fun clearAdvancedExecution()

    @Query("DELETE FROM production_intelligence_state")
''',
        '''    @Query("DELETE FROM advanced_execution_events")
    suspend fun clearAdvancedExecution()

    @Query("DELETE FROM ai_value_attribution")
    suspend fun clearAiAttribution()

    @Query("DELETE FROM production_intelligence_state")
''',
        "GovernanceDao attribution clear"
    )

    dao = replace_once(
        dao,
        '''    @Query("DELETE FROM advanced_execution_events WHERE timestampEpochMs < :beforeEpochMs")
    suspend fun pruneAdvancedExecution(beforeEpochMs: Long): Int

}
''',
        '''    @Query("DELETE FROM advanced_execution_events WHERE timestampEpochMs < :beforeEpochMs")
    suspend fun pruneAdvancedExecution(beforeEpochMs: Long): Int

    @Query("DELETE FROM ai_value_attribution WHERE status='RESOLVED' AND resolvedAtEpochMs < :beforeEpochMs")
    suspend fun pruneAiAttribution(beforeEpochMs: Long): Int

}
''',
        "GovernanceDao attribution prune"
    )

    dao_path.write_text(dao, encoding="utf-8")
    print("PATCH |", dao_path.relative_to(repo))

    db_path = repo / "app/src/main/java/com/ksp/cryptobot/data/AppDatabase.kt"
    db = db_path.read_text(encoding="utf-8")

    db = replace_once(
        db,
        '''        AdvancedExecutionEventEntity::class,
        ResearchEventEntity::class,
''',
        '''        AdvancedExecutionEventEntity::class,
        AiValueAttributionEntity::class,
        ResearchEventEntity::class,
''',
        "AppDatabase attribution entity"
    )

    db = replace_once(db, "    version = 11,\n", "    version = 12,\n", "AppDatabase version 12")

    get_anchor = '''        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
'''
    migration_insert = r'''        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    __QQ__CREATE TABLE IF NOT EXISTS ai_value_attribution(
                        fingerprint TEXT NOT NULL PRIMARY KEY,
                        createdAtEpochMs INTEGER NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL,
                        resolvedAtEpochMs INTEGER NOT NULL,
                        symbol TEXT NOT NULL,
                        strategy TEXT NOT NULL,
                        regime TEXT NOT NULL,
                        modelPath TEXT NOT NULL,
                        deterministicAction TEXT NOT NULL,
                        deterministicNotionalQuote TEXT NOT NULL,
                        lunaVerdict TEXT NOT NULL,
                        lunaRiskMultiplier TEXT NOT NULL,
                        finalVerdict TEXT NOT NULL,
                        finalRiskMultiplier TEXT NOT NULL,
                        entryPrice TEXT NOT NULL,
                        targetPrice TEXT NOT NULL,
                        stopPrice TEXT NOT NULL,
                        horizonMinutes INTEGER NOT NULL,
                        estimatedRoundTripCostRate TEXT NOT NULL,
                        lunaCostQuote TEXT NOT NULL,
                        solCostQuote TEXT NOT NULL,
                        totalAiCostQuote TEXT NOT NULL,
                        status TEXT NOT NULL,
                        resolution TEXT NOT NULL,
                        exitPrice TEXT NOT NULL,
                        deterministicNetPnlQuote TEXT NOT NULL,
                        lunaNetPnlQuote TEXT NOT NULL,
                        finalNetPnlQuote TEXT NOT NULL,
                        lunaValueAddedQuote TEXT NOT NULL,
                        solIncrementalValueQuote TEXT NOT NULL,
                        aiValueAddedQuote TEXT NOT NULL,
                        avoidedLossQuote TEXT NOT NULL,
                        missedProfitQuote TEXT NOT NULL,
                        aiGeneratedProfitQuote TEXT NOT NULL
                    )__QQ__.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_ai_value_status_created ON ai_value_attribution(status,createdAtEpochMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_ai_value_symbol_created ON ai_value_attribution(symbol,createdAtEpochMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_ai_value_model_resolved ON ai_value_attribution(modelPath,resolvedAtEpochMs)")
            }
        }

'''.replace("__QQ__", '"' * 3)
    db = replace_once(db, get_anchor, migration_insert + get_anchor, "AppDatabase migration 11 to 12")

    db = replace_once(
        db,
        '''                .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
''',
        '''                .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
''',
        "Register migration 11 to 12"
    )

    db_path.write_text(db, encoding="utf-8")
    print("PATCH |", db_path.relative_to(repo))

    router_path = repo / "app/src/main/java/com/ksp/cryptobot/intelligence/OpenAiDecisionRouter.kt"
    router = router_path.read_text(encoding="utf-8")

    router = replace_once(
        router,
        '''    val totalCostQuote: BigDecimal,
    val lunaUsage: CloudAiCallUsage? = null,
    val solUsage: CloudAiCallUsage? = null,
''',
        '''    val totalCostQuote: BigDecimal,
    val lunaVerdict: CloudAiVerdict = CloudAiVerdict.SKIPPED,
    val lunaRiskMultiplier: BigDecimal = BigDecimal.ONE,
    val lunaUsage: CloudAiCallUsage? = null,
    val solUsage: CloudAiCallUsage? = null,
''',
        "CloudAiReview Luna path fields"
    )

    router = replace_once(
        router,
        '''            totalCostQuote = totalCostUsd,
            lunaUsage = luna.usage,
            solUsage = solUsage
''',
        '''            totalCostQuote = totalCostUsd,
            lunaVerdict = luna.payload.verdict,
            lunaRiskMultiplier = luna.payload.riskMultiplier.coerceIn(BigDecimal.ZERO, BigDecimal.ONE),
            lunaUsage = luna.usage,
            solUsage = solUsage
''',
        "CloudAiReview persist Luna result"
    )

    router_path.write_text(router, encoding="utf-8")
    print("PATCH |", router_path.relative_to(repo))

    controller_path = repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt"
    controller = controller_path.read_text(encoding="utf-8")

    controller = replace_once(
        controller,
        '''import com.ksp.cryptobot.intelligence.OpenAiDecisionRouter
''',
        '''import com.ksp.cryptobot.intelligence.OpenAiDecisionRouter
import com.ksp.cryptobot.intelligence.AiValueAttributionEngine
import com.ksp.cryptobot.intelligence.AiValueAttributionSummary
''',
        "BotController M7 imports"
    )

    controller = replace_once(
        controller,
        '''    private val cloudAiRouter = OpenAiDecisionRouter(appContext, settingsStore)
    private val remoteAlertClient = RemoteAlertClient()
''',
        '''    private val cloudAiRouter = OpenAiDecisionRouter(appContext, settingsStore)
    private val aiValueAttribution = AiValueAttributionEngine(AppDatabase.get(appContext).governanceDao())
    private val remoteAlertClient = RemoteAlertClient()
''',
        "BotController attribution engine property"
    )

    controller = replace_once(
        controller,
        '''                val ticker = exchange.getTicker(symbol)
                updateStatus("[$symbol] Ticker OK. Bid=${ticker.bid}, Ask=${ticker.ask}, Last=${ticker.lastPrice}, 24hVolEUR=${ticker.volume24h.setScale(0, RoundingMode.HALF_UP)}")
                val symbolRank = proAutomationSuite.rankSymbol(ticker, recentTrades)
''',
        '''                val ticker = exchange.getTicker(symbol)
                updateStatus("[$symbol] Ticker OK. Bid=${ticker.bid}, Ask=${ticker.ask}, Last=${ticker.lastPrice}, 24hVolEUR=${ticker.volume24h.setScale(0, RoundingMode.HALF_UP)}")
                val settledAiCounterfactuals = runCatching {
                    aiValueAttribution.settleDueForSymbol(exchange, ticker)
                }.getOrDefault(0)
                if (settledAiCounterfactuals > 0) {
                    val attributionSummary = aiValueAttribution.summary()
                    updateStatus(
                        "[$symbol] M7 AI attribution resolved=$settledAiCounterfactuals. AI value=${attributionSummary.aiValueAddedQuote.setScale(4, RoundingMode.HALF_UP)}, avoided=${attributionSummary.avoidedLossQuote.setScale(4, RoundingMode.HALF_UP)}, missed=${attributionSummary.missedProfitQuote.setScale(4, RoundingMode.HALF_UP)}, ROI=${attributionSummary.aiRoi?.setScale(3, RoundingMode.HALF_UP) ?: "n/a"}, verdict=${attributionSummary.verdict}",
                        if (attributionSummary.aiValueAddedQuote < BigDecimal.ZERO) "WARN" else "INFO"
                    )
                }
                val symbolRank = proAutomationSuite.rankSymbol(ticker, recentTrades)
''',
        "BotController settle matured attribution"
    )

    controller = replace_once(
        controller,
        '''                val decision = cloudAi.decision
                if (cloudAi.review.modelPath != "DETERMINISTIC" || cloudAi.review.verdict == com.ksp.cryptobot.intelligence.CloudAiVerdict.REJECT) {
''',
        '''                aiValueAttribution.beginCloudReview(
                    deterministicDecision = deterministicDecision,
                    review = cloudAi.review,
                    ticker = ticker,
                    settings = settings,
                    strategy = research.selectedStrategy.toString(),
                    regime = research.regime.regime.toString()
                )
                val decision = cloudAi.decision
                if (cloudAi.review.modelPath != "DETERMINISTIC" || cloudAi.review.verdict == com.ksp.cryptobot.intelligence.CloudAiVerdict.REJECT) {
''',
        "BotController begin cloud attribution"
    )

    verifier_anchor = '''        try {
            V4SystemVerifier(appContext).verify(settings).forEach { check ->
'''
    verifier_insert = '''        val attribution = runCatching { aiValueAttribution.summary() }.getOrNull()
        if (attribution == null) {
            add("WARN", "AI Value Attribution", "Unable to read M7 attribution state.")
        } else {
            add(
                "PASS",
                "AI Value Attribution",
                "open=${attribution.openCounterfactuals}, resolved=${attribution.resolvedCounterfactuals}, AI_COST=${attribution.totalAiCostQuote.setScale(4, RoundingMode.HALF_UP)}, AI_VALUE_ADDED=${attribution.aiValueAddedQuote.setScale(4, RoundingMode.HALF_UP)}, AI_AVOIDED_LOSS=${attribution.avoidedLossQuote.setScale(4, RoundingMode.HALF_UP)}, AI_MISSED_PROFIT=${attribution.missedProfitQuote.setScale(4, RoundingMode.HALF_UP)}, AI_GENERATED_PROFIT=${attribution.aiGeneratedProfitQuote.setScale(4, RoundingMode.HALF_UP)}, AI_ROI=${attribution.aiRoi?.setScale(3, RoundingMode.HALF_UP) ?: "n/a"}, verdict=${attribution.verdict}. No paid AI call is made by this verifier."
            )
        }

'''
    controller = replace_once(
        controller,
        verifier_anchor,
        verifier_insert + verifier_anchor,
        "BotController attribution system verifier"
    )

    public_anchor = '''    suspend fun sendTelegramTestAlert(settings: BotSettings = settingsStore.load()): Boolean {
'''
    public_methods = '''    suspend fun loadAiValueAttributionSummary(): AiValueAttributionSummary =
        aiValueAttribution.summary()

    suspend fun loadAiValueAttributionRows(limit: Int = 100): List<AiValueAttributionEntity> =
        aiValueAttribution.recent(limit)

'''
    controller = replace_once(
        controller,
        public_anchor,
        public_methods + public_anchor,
        "BotController attribution public snapshot methods"
    )

    controller_path.write_text(controller, encoding="utf-8")
    print("PATCH |", controller_path.relative_to(repo))

    advanced_path = repo / "app/src/main/java/com/ksp/cryptobot/execution/AdvancedExecutionCoordinator.kt"
    advanced = advanced_path.read_text(encoding="utf-8")

    advanced = replace_once(
        advanced,
        '''import com.ksp.cryptobot.intelligence.CloudAiRuntime
''',
        '''import com.ksp.cryptobot.intelligence.CloudAiRuntime
import com.ksp.cryptobot.intelligence.AiValueAttributionEngine
''',
        "AdvancedExecution M7 import"
    )

    advanced = replace_once(
        advanced,
        '''    private val orderTypeOptimizer = OrderTypeOptimizer()
    private val tradeEconomics = TradeEconomicsEngine()
''',
        '''    private val orderTypeOptimizer = OrderTypeOptimizer()
    private val tradeEconomics = TradeEconomicsEngine()
    private val aiValueAttribution = AiValueAttributionEngine(governanceDao)
''',
        "AdvancedExecution M7 engine property"
    )

    advanced = replace_once(
        advanced,
        '''        var finalQuote = liquidity.finalQuote
        val cloudReview = CloudAiRuntime.snapshotFor(decision)
''',
        '''        var finalQuote = liquidity.finalQuote
        val deterministicQuoteBeforeCloud = finalQuote
        val cloudReview = CloudAiRuntime.snapshotFor(decision)
''',
        "AdvancedExecution deterministic pre-cloud quote"
    )

    advanced = replace_once(
        advanced,
        '''        record(
            "entry_economics",
''',
        '''        if (cloudReview?.lunaUsage != null) {
            runCatching {
                val effectiveCloudMultiplier = cloudReview.riskMultiplier.coerceIn(BigDecimal.ZERO, BigDecimal.ONE)
                val comparableDeterministicQuote = if (effectiveCloudMultiplier > BigDecimal.ZERO) {
                    economics.notionalQuote
                        .divide(effectiveCloudMultiplier, 8, RoundingMode.HALF_UP)
                        .min(deterministicQuoteBeforeCloud)
                } else {
                    deterministicQuoteBeforeCloud
                }
                aiValueAttribution.linkExecutionEconomics(
                    fingerprint = cloudReview.fingerprint,
                    deterministicNotionalQuote = comparableDeterministicQuote,
                    assessment = economics
                )
            }
        }
        record(
            "entry_economics",
''',
        "AdvancedExecution link M5 economics to M7"
    )

    advanced_path.write_text(advanced, encoding="utf-8")
    print("PATCH |", advanced_path.relative_to(repo))

    changed = set(os.popen(f'cd "{repo}" && git diff --name-only -- app').read().splitlines())
    untracked = set(os.popen(f'cd "{repo}" && git ls-files --others --exclude-standard -- app').read().splitlines())
    all_changed = changed | untracked

    allowed = {
        NEW_ENTITY,
        NEW_ENGINE,
        NEW_TEST,
        "app/src/main/java/com/ksp/cryptobot/data/GovernanceDao.kt",
        "app/src/main/java/com/ksp/cryptobot/data/AppDatabase.kt",
        "app/src/main/java/com/ksp/cryptobot/intelligence/OpenAiDecisionRouter.kt",
        "app/src/main/java/com/ksp/cryptobot/core/BotController.kt",
        "app/src/main/java/com/ksp/cryptobot/execution/AdvancedExecutionCoordinator.kt",
    }

    unexpected = sorted(all_changed - allowed)
    missing = sorted(allowed - all_changed)
    if unexpected:
        fail("Unexpected M7 app changes: " + ", ".join(unexpected))
    if missing:
        fail("Expected M7 app changes missing: " + ", ".join(missing))

    print("PASS | M7 patch changed only approved attribution/data/integration files.")

if __name__ == "__main__":
    main()
