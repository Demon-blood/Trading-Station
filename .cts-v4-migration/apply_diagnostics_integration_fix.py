#!/usr/bin/env python3
"""Crypto TradeStation v4 Diagnostics + Integration Fix.

Target baseline:
  Demon-blood/Trading-Station main @ 9081c5aa5ed73be8f9f3a72f7e7981901af9233b

Executed by the canonical Android v4 GitHub Actions workflow AFTER apply_milestone6.py.
It patches the effective checked-out base sources and cumulative v4 migration overlay
before Kotlin compilation/tests/APK assembly, so migration-owned files cannot erase the fix.
"""
from __future__ import annotations

import sys
from pathlib import Path

BASELINE_HEAD = "9081c5aa5ed73be8f9f3a72f7e7981901af9233b"


def fail(message: str) -> None:
    raise SystemExit(f"[CTS diagnostics fix] {message}")


def require(path: Path) -> None:
    if not path.exists():
        fail(f"Required path not found: {path}")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        fail(f"Cannot patch {label}: expected exactly one baseline marker, found {count}.")
    return text.replace(old, new, 1)


def write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content.rstrip() + "\n", encoding="utf-8")


SAFE_MODE_SOURCE = r'''package com.ksp.cryptobot.governance

import com.ksp.cryptobot.core.BotMode
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.data.GovernanceEventEntity
import com.ksp.cryptobot.data.TradeEntity
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

class RiskBudgetManager {
    fun evaluate(settings: BotSettings, recentTrades: List<TradeEntity>, requestedQuoteEur: Double = 0.0): RiskBudgetAssessment {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val todayPnl = recentTrades.asSequence()
            .filter { it.timestampEpochMs >= start && it.side.equals("SELL", ignoreCase = true) }
            .sumOf { it.realizedPnlEur.toDoubleOrNull() ?: 0.0 }
        val configuredLossLimit = abs(settings.maxDailyLossEur.toDouble())
        val dailyBudget = if (configuredLossLimit > 0.0) minOf(configuredLossLimit, 10.0) else 10.0
        val used = maxOf(0.0, -todayPnl)
        val remaining = maxOf(0.0, dailyBudget - used)
        if (remaining <= 0.0) {
            return RiskBudgetAssessment(0.0, true, used, remaining, "daily risk budget exhausted: used €%.2f/€%.2f".format(used, dailyBudget))
        }
        val multiplier = (remaining / maxOf(1.0, dailyBudget)).coerceIn(0.10, 1.0)
        return RiskBudgetAssessment(multiplier, false, used, remaining, "daily risk budget remaining €%.2f/€%.2f; size multiplier %.2f; requested €%.2f".format(remaining, dailyBudget, multiplier, requestedQuoteEur))
    }
}

/**
 * Safe mode reacts only to causative operational failures. Derived governance
 * decisions (for example production_ai_evaluation/safe_mode_event) must never
 * count as new failures, otherwise the controller can self-latch indefinitely.
 */
class SafeModeController {
    private val causativeErrorTypes = setOf(
        "anomaly_event",
        "watchdog_error",
        "order_error",
        "handoff_protective_exit_failure"
    )

    fun evaluate(
        settings: BotSettings,
        recentEvents: List<GovernanceEventEntity>,
        realizedToday: Double,
        anomaly: AnomalyAssessment,
        nowEpochMs: Long = System.currentTimeMillis()
    ): SafeModeAssessment {
        val modeLive = settings.mode == BotMode.LIVE_AUTO || settings.mode == BotMode.LIVE_CONFIRM
        val dailyLimit = abs(settings.maxDailyLossEur.toDouble())
        val recentErrorWindowMs = 60L * 60L * 1000L
        val recentBad = recentEvents.asSequence()
            .filter { event ->
                val age = nowEpochMs - event.timestampEpochMs
                age in 0L..recentErrorWindowMs
            }
            .count { it.eventType in causativeErrorTypes }

        val unresolvedProtectiveFailure = recentEvents.take(80).any {
            it.eventType == "handoff_protective_exit_failure" &&
                nowEpochMs - it.timestampEpochMs in 0L..(6L * 60L * 60L * 1000L)
        }

        return when {
            // Protective-exit and configured loss-limit protection stay hard even
            // when automatic anomaly/error escalation is disabled.
            modeLive && unresolvedProtectiveFailure ->
                SafeModeAssessment("PAPER_ONLY", "protective source exit/reduction failed within the last 6h; new live entries paused until execution health is re-established", -10, 0.0, true)
            modeLive && dailyLimit > 0.0 && realizedToday <= -dailyLimit ->
                SafeModeAssessment("PAUSED", "live daily loss limit reached; real new entries paused", -10, 0.0, true)
            settings.enableAutoSafeMode && recentBad >= 6 ->
                SafeModeAssessment("PAPER_ONLY", "multiple causative operational errors within the last hour ($recentBad)", -8, 0.0, true)
            settings.enableAutoSafeMode && !anomaly.allowed && anomaly.severity in setOf("HIGH", "CRITICAL") ->
                SafeModeAssessment("CONSERVATIVE", "market-data anomaly detected", -6, 0.45, false)
            else -> SafeModeAssessment("NORMAL", "normal", 0, 1.0, false)
        }
    }
}
'''

NEWS_HEALTH_SOURCE = r'''package com.ksp.cryptobot.news

/**
 * Process-local health/cooldown truth for external news providers.
 *
 * The cooldown durations are Crypto TradeStation retry policy, not provider
 * quota guarantees. They prevent a broken/auth-limited provider from being hit
 * on every symbol during every scan while keeping the rest of the news ensemble
 * fail-neutral.
 */
data class NewsProviderHealth(
    val provider: String,
    val status: String = "READY",
    val lastAttemptEpochMs: Long = 0L,
    val lastSuccessEpochMs: Long = 0L,
    val lastFailureEpochMs: Long = 0L,
    val consecutiveFailures: Int = 0,
    val cooldownUntilEpochMs: Long = 0L,
    val lastArticleCount: Int = 0,
    val lastError: String = ""
) {
    fun coolingDown(nowEpochMs: Long = System.currentTimeMillis()): Boolean = cooldownUntilEpochMs > nowEpochMs
}

object NewsProviderHealthRegistry {
    private const val FIVE_MINUTES = 5L * 60L * 1000L
    private const val TEN_MINUTES = 10L * 60L * 1000L
    private const val THIRTY_MINUTES = 30L * 60L * 1000L
    private const val SIX_HOURS = 6L * 60L * 60L * 1000L
    private const val MAX_COOLDOWN = 12L * 60L * 60L * 1000L

    private val lock = Any()
    private val states = linkedMapOf<String, NewsProviderHealth>()

    fun shouldAttempt(provider: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        val current = states[provider] ?: return@synchronized true
        current.cooldownUntilEpochMs <= nowEpochMs
    }

    fun recordAttempt(provider: String, nowEpochMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        val current = states[provider] ?: NewsProviderHealth(provider)
        states[provider] = current.copy(
            status = if (current.cooldownUntilEpochMs > nowEpochMs) "COOLDOWN" else "CHECKING",
            lastAttemptEpochMs = nowEpochMs
        )
    }

    fun recordSuccess(provider: String, articleCount: Int, nowEpochMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        val current = states[provider] ?: NewsProviderHealth(provider)
        states[provider] = current.copy(
            status = if (articleCount > 0) "HEALTHY" else "EMPTY",
            lastAttemptEpochMs = nowEpochMs,
            lastSuccessEpochMs = nowEpochMs,
            consecutiveFailures = 0,
            cooldownUntilEpochMs = 0L,
            lastArticleCount = articleCount.coerceAtLeast(0),
            lastError = ""
        )
    }

    fun recordFailure(provider: String, error: Throwable, nowEpochMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        val current = states[provider] ?: NewsProviderHealth(provider)
        val failures = (current.consecutiveFailures + 1).coerceAtMost(20)
        val base = baseCooldown(error)
        val multiplier = 1L shl (failures - 1).coerceIn(0, 3)
        val cooldown = (base * multiplier).coerceAtMost(MAX_COOLDOWN)
        states[provider] = current.copy(
            status = "COOLDOWN",
            lastAttemptEpochMs = nowEpochMs,
            lastFailureEpochMs = nowEpochMs,
            consecutiveFailures = failures,
            cooldownUntilEpochMs = nowEpochMs + cooldown,
            lastArticleCount = 0,
            lastError = (error.message ?: error.javaClass.simpleName).take(280)
        )
    }

    fun healthFor(provider: String, nowEpochMs: Long = System.currentTimeMillis()): NewsProviderHealth? = synchronized(lock) {
        normalize(states[provider], nowEpochMs)
    }

    fun snapshot(nowEpochMs: Long = System.currentTimeMillis()): List<NewsProviderHealth> = synchronized(lock) {
        states.values.mapNotNull { normalize(it, nowEpochMs) }.sortedBy { it.provider }
    }

    internal fun resetForTests() = synchronized(lock) { states.clear() }

    private fun normalize(value: NewsProviderHealth?, nowEpochMs: Long): NewsProviderHealth? {
        value ?: return null
        return if (value.status == "COOLDOWN" && value.cooldownUntilEpochMs <= nowEpochMs) {
            value.copy(status = "READY")
        } else value
    }

    private fun baseCooldown(error: Throwable): Long {
        val message = (error.message ?: "").lowercase()
        return when {
            Regex("\\b(401|403)\\b").containsMatchIn(message) -> SIX_HOURS
            Regex("\\b429\\b").containsMatchIn(message) -> THIRTY_MINUTES
            Regex("\\b5\\d\\d\\b").containsMatchIn(message) -> TEN_MINUTES
            else -> FIVE_MINUTES
        }
    }
}
'''

NEWS_CLIENT_SOURCE = r'''package com.ksp.cryptobot.news

import com.ksp.cryptobot.core.NewsArticle

interface NewsClient {
    suspend fun latestCryptoNews(symbol: String): List<NewsArticle>
}

class NoopNewsClient : NewsClient {
    override suspend fun latestCryptoNews(symbol: String): List<NewsArticle> = emptyList()
}

class CompositeNewsClient(private val providers: List<NewsClient>) : NewsClient {
    override suspend fun latestCryptoNews(symbol: String): List<NewsArticle> {
        return providers
            .flatMap { provider ->
                val label = providerLabel(provider)
                if (!NewsProviderHealthRegistry.shouldAttempt(label)) {
                    emptyList()
                } else {
                    NewsProviderHealthRegistry.recordAttempt(label)
                    runCatching { provider.latestCryptoNews(symbol) }
                        .fold(
                            onSuccess = { rows ->
                                NewsProviderHealthRegistry.recordSuccess(label, rows.size)
                                rows
                            },
                            onFailure = { error ->
                                NewsProviderHealthRegistry.recordFailure(label, error)
                                emptyList()
                            }
                        )
                }
            }
            .distinctBy { it.url.ifBlank { it.title.lowercase() } }
            .sortedByDescending { it.publishedAt }
            .take(40)
    }

    private fun providerLabel(provider: NewsClient): String = when (provider.javaClass.simpleName) {
        "GdeltNewsClient" -> "GDELT"
        "RssFeedNewsClient" -> "RSS"
        "CryptoPanicNewsClient" -> "CryptoPanic"
        "MarketauxNewsClient" -> "Marketaux"
        "NewsDataNewsClient" -> "NewsData.io"
        "GNewsNewsClient" -> "GNews"
        "GuardianNewsClient" -> "Guardian"
        "NewsApiClient" -> "NewsAPI"
        else -> provider.javaClass.simpleName.ifBlank { "UnknownNewsProvider" }
    }
}
'''

V4_VERIFIER_SOURCE = r'''package com.ksp.cryptobot.release

import android.content.Context
import com.ksp.cryptobot.cloudshare.CloudShareSettingsStore
import com.ksp.cryptobot.cloudshare.CloudShareSyncEngine
import com.ksp.cryptobot.core.BotMode
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.data.AppDatabase
import com.ksp.cryptobot.news.NewsProviderHealthRegistry
import com.ksp.cryptobot.research.ResearchSettingsStore
import com.ksp.cryptobot.settings.AppSettingsStore

class V4SystemVerifier(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabase.get(appContext)
    private val dao = db.dao()
    private val governance = db.governanceDao()
    private val research = db.researchDao()
    private val cloudSettings = CloudShareSettingsStore(appContext)
    private val researchSettings = ResearchSettingsStore(appContext)
    private val appSettings = AppSettingsStore(appContext)

    suspend fun verify(settings: BotSettings): List<V4VerificationItem> {
        val out = mutableListOf<V4VerificationItem>()
        fun add(status: String, name: String, detail: String) { out += V4VerificationItem(status, name, detail) }

        val dbVersion = runCatching { db.openHelper.readableDatabase.version }.getOrDefault(-1)
        add(if (dbVersion == V4ReleaseInfo.ROOM_SCHEMA_VERSION) "PASS" else "FAIL", "Room v4 schema", "databaseVersion=$dbVersion expected=${V4ReleaseInfo.ROOM_SCHEMA_VERSION}; migrations 6→7→8→9→10→11")

        val release = runCatching { ReleaseIntegrityChecker(appContext).snapshot() }.getOrNull()
        if (release == null) add("WARN", "Install/signing lineage", "Package signing information unavailable.")
        else {
            val versionOk = release.versionName == V4ReleaseInfo.VERSION_NAME && release.versionCode >= V4ReleaseInfo.VERSION_CODE
            add(if (versionOk) "PASS" else "WARN", "Release identity", release.detail)
            add(if (release.signerStable) "PASS" else "FAIL", "Signing lineage", if (release.signerStable) "Current signer matches the first trusted v4 signer on this installation." else "APK signer changed since the v4 trust record was created.")
            add(if (release.debuggable) "WARN" else "PASS", "Release debuggability", if (release.debuggable) "Debuggable APK detected. Use the release build for unattended LIVE_AUTO." else "Non-debuggable build detected.")
        }

        // Persisted settings truth: never report a save as successful solely from
        // in-memory Compose state.
        val persistedSettings = runCatching { appSettings.load() }.getOrNull()
        val saveTruth = appSettings.lastSaveVerification()
        val inMemoryMatchesDisk = persistedSettings == settings
        val settingsStatus = when {
            persistedSettings == null -> "FAIL"
            saveTruth.timestampEpochMs == 0L -> "WARN"
            !saveTruth.committed || !saveTruth.exactMatch -> "FAIL"
            !inMemoryMatchesDisk -> "WARN"
            else -> "PASS"
        }
        add(
            settingsStatus,
            "Effective settings persistence",
            "lastCommit=${saveTruth.committed}, lastExactReload=${saveTruth.exactMatch}, lastSaveEpochMs=${saveTruth.timestampEpochMs}, uiMatchesDisk=$inMemoryMatchesDisk, effectiveMode=${persistedSettings?.mode ?: "LOAD_FAILED"}"
        )
        add(
            if (persistedSettings != null) "PASS" else "FAIL",
            "Effective settings truth",
            persistedSettings?.toString() ?: "Unable to load persisted BotSettings."
        )

        val providerConfig = listOf(
            "GDELT=PUBLIC_NO_KEY",
            "RSS=PUBLIC_NO_KEY",
            "CryptoPanic=${if (!appSettings.cryptoPanicApiKey().isNullOrBlank()) "CONFIGURED" else "NO_KEY"}",
            "Marketaux=${if (!appSettings.marketauxApiKey().isNullOrBlank()) "CONFIGURED" else "NO_KEY"}",
            "NewsData.io=${if (!appSettings.newsDataApiKey().isNullOrBlank()) "CONFIGURED" else "NO_KEY"}",
            "GNews=${if (!appSettings.gNewsApiKey().isNullOrBlank()) "CONFIGURED" else "NO_KEY"}",
            "Guardian=${if (!appSettings.guardianApiKey().isNullOrBlank()) "CONFIGURED" else "NO_KEY"}",
            "NewsAPI=${appSettings.newsApiKey()?.split(',', ';', '\n')?.count { it.trim().isNotBlank() } ?: 0} key(s)"
        )
        add("PASS", "News provider configuration", providerConfig.joinToString(", "))

        val providerHealth = NewsProviderHealthRegistry.snapshot()
        if (providerHealth.isEmpty()) {
            add("WARN", "News provider runtime health", "No provider attempt has occurred in this app process yet. Run Scan News or a normal scan, then Verify v4 again.")
        } else {
            providerHealth.forEach { health ->
                val status = when {
                    health.coolingDown() -> "WARN"
                    health.lastSuccessEpochMs > 0L && health.lastSuccessEpochMs >= health.lastFailureEpochMs -> "PASS"
                    health.lastFailureEpochMs > 0L -> "WARN"
                    else -> "WARN"
                }
                add(
                    status,
                    "News health • ${health.provider}",
                    "status=${health.status}, articles=${health.lastArticleCount}, failures=${health.consecutiveFailures}, lastAttempt=${health.lastAttemptEpochMs}, lastSuccess=${health.lastSuccessEpochMs}, lastFailure=${health.lastFailureEpochMs}, cooldownUntil=${health.cooldownUntilEpochMs}${health.lastError.takeIf { it.isNotBlank() }?.let { ", error=$it" } ?: ""}"
                )
            }
        }

        val gov = runCatching { governance.recentEvents(10_000) }.getOrDefault(emptyList())
        val execution = runCatching { governance.recentExecutionQuality(10_000) }.getOrDefault(emptyList())
        val advanced = runCatching { governance.recentAdvancedExecution(10_000) }.getOrDefault(emptyList())
        val safeMode = runCatching { governance.stateValue("safe_mode_level") }.getOrNull().orEmpty().ifBlank { "not-yet-evaluated" }
        val heartbeat = runCatching { governance.stateValue("service_heartbeat_epoch_ms")?.toLongOrNull() }.getOrNull()
        val heartbeatAge = heartbeat?.let { System.currentTimeMillis() - it }
        add("PASS", "Governance ledger", "events=${gov.size}, executionQuality=${execution.size}, advancedExecution=${advanced.size}, safeMode=$safeMode")
        add(if (heartbeatAge == null || heartbeatAge > 180_000L) "WARN" else "PASS", "Foreground watchdog", if (heartbeatAge == null) "No service heartbeat recorded yet." else "heartbeatAge=${heartbeatAge / 1000L}s")

        val researchEvents = runCatching { research.recentEvents(10_000) }.getOrDefault(emptyList())
        val profiles = runCatching { research.profiles() }.getOrDefault(emptyList())
        add("PASS", "Research engine persistence", "events=${researchEvents.size}, profiles=${profiles.size}, enabled=${researchSettings.enabled()}, livePromotion=${researchSettings.researchPromotionInLive()}")
        add(if (!researchSettings.researchPromotionInLive()) "PASS" else "WARN", "Research LIVE promotion", if (researchSettings.researchPromotionInLive()) "Research-created LIVE entry promotion is enabled. M3/M4 guards still apply, but default is OFF." else "Research-created LIVE entry promotion remains OFF by default.")

        val cloud = runCatching { CloudShareSyncEngine(appContext).diagnostics() }.getOrNull()
        if (!cloudSettings.enabled) add("PASS", "CloudShare", "CloudShare disabled; local trading remains independent.")
        else if (cloud == null) add("WARN", "CloudShare", "Enabled but diagnostics could not be read.")
        else {
            val https = cloudSettings.apiUrl.startsWith("https://")
            val registered = cloud.registered
            add(if (https && registered) "PASS" else "WARN", "CloudShare", "registered=$registered, https=$https, pending=${cloud.pendingOutbox}, downloaded=${cloud.downloadedIntelligence}, collectiveSamples=${cloud.collectiveSamples}, contributors=${cloud.contributors}")
        }

        // Persisted evidence matrix for the requested end-to-end path. A fresh
        // install is WARN rather than a false PASS until runtime evidence exists.
        val trades = runCatching { dao.allTradesSnapshot() }.getOrDefault(emptyList())
        val completedExits = trades.filter { it.side.equals("SELL", ignoreCase = true) }
        val learningProfiles = runCatching { dao.learnedSymbolProfilesSnapshot() }.getOrDefault(emptyList())
        val learningAudit = runCatching { dao.selfLearningAudit(100) }.getOrDefault(emptyList())
        val m3Evaluations = gov.count { it.eventType == "production_ai_evaluation" }
        val newsSuccessEvidence = providerHealth.any { it.lastSuccessEpochMs > 0L }
        val settingsEvidence = persistedSettings != null && saveTruth.committed && saveTruth.exactMatch
        val researchEvidence = researchEvents.isNotEmpty()
        val m3Evidence = m3Evaluations > 0
        val m4Evidence = execution.isNotEmpty() || advanced.isNotEmpty()
        val lifecycleEvidence = completedExits.isNotEmpty()
        val learningEvidence = learningProfiles.isNotEmpty() || learningAudit.isNotEmpty()

        add(if (settingsEvidence) "PASS" else "WARN", "E2E • settings → persisted effective config", "evidence=$settingsEvidence")
        add(if (newsSuccessEvidence) "PASS" else "WARN", "E2E • data/news acquisition", "providerSuccess=$newsSuccessEvidence, trackedProviders=${providerHealth.size}")
        add(if (researchEvidence) "PASS" else "WARN", "E2E • strategy/research", "researchEvents=${researchEvents.size}, profiles=${profiles.size}")
        add(if (m3Evidence) "PASS" else "WARN", "E2E • M3 governance", "production_ai_evaluation events=$m3Evaluations")
        add(if (m4Evidence) "PASS" else "WARN", "E2E • M4 execution", "executionQuality=${execution.size}, advancedExecution=${advanced.size}")
        add(if (lifecycleEvidence) "PASS" else "WARN", "E2E • lifecycle completed outcomes", "completed SELL outcomes=${completedExits.size}")
        add(if (learningEvidence) "PASS" else "WARN", "E2E • persistence/learning", "learnedProfiles=${learningProfiles.size}, learningAudit=${learningAudit.size}")
        val e2eAll = settingsEvidence && newsSuccessEvidence && researchEvidence && m3Evidence && m4Evidence && lifecycleEvidence && learningEvidence
        add(if (e2eAll) "PASS" else "WARN", "End-to-end wiring evidence", if (e2eAll) "Persisted runtime evidence exists for every requested stage." else "Not all stages have persisted runtime evidence yet. This is intentionally not reported as fully integrated until every stage above passes.")

        add(if (settings.mode != BotMode.LIVE_AUTO || settings.liveTradingAcknowledged) "PASS" else "FAIL", "LIVE_AUTO acknowledgement", "mode=${settings.mode}, acknowledged=${settings.liveTradingAcknowledged}")
        add(if (settings.mode != BotMode.LIVE_AUTO || settings.autoStopLossEnabled) "PASS" else "FAIL", "Hard stop-loss", "autoStopLossEnabled=${settings.autoStopLossEnabled}")
        add(if (settings.mode != BotMode.LIVE_AUTO || (settings.enableBacktestGate && settings.enableForwardTestGate)) "PASS" else "FAIL", "Validation gates", "backtest=${settings.enableBacktestGate}, forwardTest=${settings.enableForwardTestGate}")
        add("PASS", "Storage audit", V4MaintenanceManager(appContext).storageAudit())
        add("PASS", "Migration completion", "${V4ReleaseInfo.MIGRATION_STAGE_COMPLETE}/${V4ReleaseInfo.MIGRATION_STAGE_COUNT} stages integrated; protocol=${V4ReleaseInfo.CLOUDSHARE_PROTOCOL}")
        return out
    }
}
'''

SAFE_MODE_TEST = r'''package com.ksp.cryptobot.governance

import com.ksp.cryptobot.core.BotMode
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.data.GovernanceEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeModeControllerTest {
    private val controller = SafeModeController()
    private val normalAnomaly = AnomalyAssessment(true, "INFO", "normal")

    @Test
    fun derivedHighSeverityGovernanceEventsDoNotSelfLatchSafeMode() {
        val now = 1_800_000_000_000L
        val derived = (1..12).flatMap { index ->
            listOf(
                GovernanceEventEntity(timestampEpochMs = now - index * 1_000L, eventType = "production_ai_evaluation", severity = "HIGH", blocked = true, reason = "derived"),
                GovernanceEventEntity(timestampEpochMs = now - index * 1_000L, eventType = "safe_mode_event", severity = "HIGH", blocked = true, reason = "derived")
            )
        }
        val result = controller.evaluate(BotSettings(mode = BotMode.LIVE_AUTO), derived, 0.0, normalAnomaly, now)
        assertEquals("NORMAL", result.level)
        assertFalse(result.blockLiveEntries)
    }

    @Test
    fun repeatedCausativeErrorsStillEscalateWhenAutoSafeModeEnabled() {
        val now = 1_800_000_000_000L
        val errors = (1..6).map { index ->
            GovernanceEventEntity(timestampEpochMs = now - index * 1_000L, eventType = "order_error", severity = "HIGH", blocked = true, reason = "submit failed")
        }
        val result = controller.evaluate(BotSettings(mode = BotMode.LIVE_AUTO, enableAutoSafeMode = true), errors, 0.0, normalAnomaly, now)
        assertEquals("PAPER_ONLY", result.level)
        assertTrue(result.blockLiveEntries)
    }

    @Test
    fun oldCausativeErrorsAgeOutInsteadOfLatchingForever() {
        val now = 1_800_000_000_000L
        val errors = (1..10).map { index ->
            GovernanceEventEntity(timestampEpochMs = now - 2L * 60L * 60L * 1_000L - index, eventType = "order_error", severity = "HIGH", blocked = true, reason = "old")
        }
        val result = controller.evaluate(BotSettings(mode = BotMode.LIVE_AUTO, enableAutoSafeMode = true), errors, 0.0, normalAnomaly, now)
        assertEquals("NORMAL", result.level)
    }
}
'''

EXECUTION_GUARD_TEST = r'''package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.BotMode
import com.ksp.cryptobot.core.SignalAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionGuardSafeModeTest {
    @Test
    fun safeModeBlocksNewLiveEntriesButNotSellExits() {
        assertTrue(productionSafeModeBlocks(BotMode.LIVE_AUTO, SignalAction.BUY, true))
        assertTrue(productionSafeModeBlocks(BotMode.LIVE_AUTO, SignalAction.SMALL_BUY, true))
        assertFalse(productionSafeModeBlocks(BotMode.LIVE_AUTO, SignalAction.SELL, true))
        assertFalse(productionSafeModeBlocks(BotMode.PAPER, SignalAction.BUY, true))
        assertTrue(isNewEntryAction(SignalAction.BUY))
        assertFalse(isNewEntryAction(SignalAction.SELL))
    }
}
'''

NEWS_HEALTH_TEST = r'''package com.ksp.cryptobot.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NewsProviderHealthRegistryTest {
    @Before
    fun reset() = NewsProviderHealthRegistry.resetForTests()

    @Test
    fun http429CreatesCooldownAndSuppressesImmediateRetry() {
        val now = 1_800_000_000_000L
        assertTrue(NewsProviderHealthRegistry.shouldAttempt("GNews", now))
        NewsProviderHealthRegistry.recordAttempt("GNews", now)
        NewsProviderHealthRegistry.recordFailure("GNews", IllegalStateException("GNews HTTP 429: quota"), now)
        val health = requireNotNull(NewsProviderHealthRegistry.healthFor("GNews", now))
        assertEquals("COOLDOWN", health.status)
        assertFalse(NewsProviderHealthRegistry.shouldAttempt("GNews", now + 1_000L))
        assertTrue(health.cooldownUntilEpochMs >= now + 30L * 60L * 1_000L)
    }

    @Test
    fun successClearsFailureState() {
        val now = 1_800_000_000_000L
        NewsProviderHealthRegistry.recordFailure("GDELT", IllegalStateException("GDELT HTTP 503"), now)
        NewsProviderHealthRegistry.recordSuccess("GDELT", 7, now + 20L * 60L * 1_000L)
        val health = requireNotNull(NewsProviderHealthRegistry.healthFor("GDELT", now + 20L * 60L * 1_000L))
        assertEquals("HEALTHY", health.status)
        assertEquals(0, health.consecutiveFailures)
        assertEquals(7, health.lastArticleCount)
        assertTrue(NewsProviderHealthRegistry.shouldAttempt("GDELT", now + 20L * 60L * 1_000L))
    }
}
'''

LEARNING_OUTCOME_TEST = r'''package com.ksp.cryptobot.learning

import com.ksp.cryptobot.core.BotMode
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.data.TradeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletedLearningOutcomeTest {
    private fun trade(side: String, paper: Boolean, pnl: String, ts: Long) = TradeEntity(
        symbol = "BTCEUR",
        side = side,
        quantity = "0.001",
        priceEur = "100000",
        feeEur = "0.10",
        paper = paper,
        realizedPnlEur = pnl,
        timestampEpochMs = ts
    )

    @Test
    fun oneBuySellRoundTripCountsAsOneOutcomeNotTwoSamples() {
        val rows = listOf(
            trade("BUY", true, "0.00", 1L),
            trade("SELL", true, "2.50", 2L)
        )
        val outcomes = completedOutcomeTradesForLearning(rows, BotSettings(mode = BotMode.PAPER, selfLearningPaperAndLiveSeparated = true))
        assertEquals(1, outcomes.size)
        assertEquals("SELL", outcomes.single().side)
        assertEquals("2.50", outcomes.single().realizedPnlEur)
    }

    @Test
    fun separatedLearningUsesOnlyCurrentPaperLiveMode() {
        val rows = listOf(
            trade("SELL", true, "1.00", 1L),
            trade("SELL", false, "-1.00", 2L)
        )
        val paper = completedOutcomeTradesForLearning(rows, BotSettings(mode = BotMode.PAPER, selfLearningPaperAndLiveSeparated = true))
        val live = completedOutcomeTradesForLearning(rows, BotSettings(mode = BotMode.LIVE_AUTO, selfLearningPaperAndLiveSeparated = true))
        assertEquals(1, paper.size)
        assertTrue(paper.single().paper)
        assertEquals(1, live.size)
        assertFalse(live.single().paper)
    }

    private fun assertFalse(value: Boolean) = org.junit.Assert.assertFalse(value)
}
'''


GOVERNANCE_ENTRY_TEST = r'''package com.ksp.cryptobot.governance

import com.ksp.cryptobot.core.SignalAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionEntryGovernorsTest {
    @Test
    fun killRiskAndSafeModeAreEntryOnly() {
        assertTrue(entryOnlyGovernorsBlock(SignalAction.BUY, killAllowed = false, riskBlocked = false, liveSafeBlocked = false))
        assertTrue(entryOnlyGovernorsBlock(SignalAction.SMALL_BUY, killAllowed = true, riskBlocked = true, liveSafeBlocked = false))
        assertTrue(entryOnlyGovernorsBlock(SignalAction.BUY, killAllowed = true, riskBlocked = false, liveSafeBlocked = true))
        assertFalse(entryOnlyGovernorsBlock(SignalAction.SELL, killAllowed = false, riskBlocked = true, liveSafeBlocked = true))
    }
}
'''


def patch_entry_governors(repo: Path) -> None:
    """Keep risk-reducing SELL actions eligible through M3/M4 entry brakes."""
    production_path = repo / ".cts-v4-migration/app/src/main/java/com/ksp/cryptobot/governance/ProductionIntelligenceEngine.kt"
    require(production_path)
    production = production_path.read_text(encoding="utf-8")
    if "internal fun entryOnlyGovernorsBlock(" not in production:
        production = replace_once(
            production,
            "class ProductionIntelligenceEngine(private val dao: GovernanceDao) {\n",
            '''internal fun entryOnlyGovernorsBlock(
    action: SignalAction,
    killAllowed: Boolean,
    riskBlocked: Boolean,
    liveSafeBlocked: Boolean
): Boolean {
    val isEntry = action in setOf(SignalAction.BUY, SignalAction.SMALL_BUY)
    return isEntry && (!killAllowed || riskBlocked || liveSafeBlocked)
}

class ProductionIntelligenceEngine(private val dao: GovernanceDao) {
''',
            "ProductionIntelligence entry-governor helper"
        )
    production = replace_once(
        production,
        '''        val adjustment = (safe.scoreAdjustment + quality.scoreAdjustment + counterAdj).coerceIn(-12, 6)
        val liveMode = settings.mode == BotMode.LIVE_AUTO || settings.mode == BotMode.LIVE_CONFIRM
        val blocked = !anomaly.allowed || !kill.allowed || risk.blocked || (liveMode && safe.blockLiveEntries)
        val sizeMultiplier = (safe.sizeMultiplier * risk.multiplier).coerceIn(0.0, 1.0)
''',
        '''        val adjustment = (safe.scoreAdjustment + quality.scoreAdjustment + counterAdj).coerceIn(-12, 6)
        val liveMode = settings.mode == BotMode.LIVE_AUTO || settings.mode == BotMode.LIVE_CONFIRM
        val entryGovernanceBlocked = entryOnlyGovernorsBlock(
            decision.finalAction,
            killAllowed = kill.allowed,
            riskBlocked = risk.blocked,
            liveSafeBlocked = liveMode && safe.blockLiveEntries
        )
        val blocked = !anomaly.allowed || entryGovernanceBlocked
        val sizeMultiplier = (safe.sizeMultiplier * risk.multiplier).coerceIn(0.0, 1.0)
''',
        "ProductionIntelligence entry-only block calculation"
    )
    production_path.write_text(production, encoding="utf-8")

    guard_path = repo / ".cts-v4-migration/app/src/main/java/com/ksp/cryptobot/execution/ExecutionGuard.kt"
    require(guard_path)
    guard = guard_path.read_text(encoding="utf-8")
    if "internal fun isNewEntryAction(" not in guard:
        guard = replace_once(
            guard,
            '''internal fun productionSafeModeBlocks(mode: BotMode, action: SignalAction, blockLiveEntries: Boolean): Boolean =
    mode != BotMode.PAPER && blockLiveEntries && action in setOf(SignalAction.BUY, SignalAction.SMALL_BUY)
''',
            '''internal fun isNewEntryAction(action: SignalAction): Boolean = action in setOf(SignalAction.BUY, SignalAction.SMALL_BUY)

internal fun productionSafeModeBlocks(mode: BotMode, action: SignalAction, blockLiveEntries: Boolean): Boolean =
    mode != BotMode.PAPER && blockLiveEntries && isNewEntryAction(action)
''',
            "ExecutionGuard entry-action helper"
        )
    guard = replace_once(
        guard,
        '''        if (settings.maxTradesPerDay > 0 && todaysTrades.size >= settings.maxTradesPerDay) {
            return false to "Daily trade limit reached: ${todaysTrades.size}/${settings.maxTradesPerDay}."
        }
''',
        '''        if (isNewEntryAction(decision.finalAction) && settings.maxTradesPerDay > 0 && todaysTrades.size >= settings.maxTradesPerDay) {
            return false to "Daily entry limit reached: ${todaysTrades.size}/${settings.maxTradesPerDay}. SELL exits remain allowed."
        }
''',
        "ExecutionGuard daily entry limit"
    )
    guard = replace_once(
        guard,
        '''        if (tradesLastHour.size >= settings.maxTradesPerHour) {
            return false to "Hourly trade limit reached: ${tradesLastHour.size}/${settings.maxTradesPerHour}."
        }
''',
        '''        if (isNewEntryAction(decision.finalAction) && tradesLastHour.size >= settings.maxTradesPerHour) {
            return false to "Hourly entry limit reached: ${tradesLastHour.size}/${settings.maxTradesPerHour}. SELL exits remain allowed."
        }
''',
        "ExecutionGuard hourly entry limit"
    )
    guard = replace_once(
        guard,
        '''        if (realizedLoss >= settings.maxDailyLossEur) {
            return false to "Daily loss guard active: €$realizedLoss >= €${settings.maxDailyLossEur}."
        }

        val last = dao.lastTradeForSymbol(decision.symbol)
        if (last != null) {
''',
        '''        if (isNewEntryAction(decision.finalAction) && realizedLoss >= settings.maxDailyLossEur) {
            return false to "Daily loss guard active for new entries: €$realizedLoss >= €${settings.maxDailyLossEur}. SELL exits remain allowed."
        }

        val last = dao.lastTradeForSymbol(decision.symbol)
        if (last != null && isNewEntryAction(decision.finalAction)) {
''',
        "ExecutionGuard loss/cooldown entry-only"
    )
    guard_path.write_text(guard, encoding="utf-8")


def patch(repo: Path) -> None:
    canonical = repo / ".github/workflows/android-v4-build.yml"
    migration = repo / ".cts-v4-migration/apply_milestone6.py"
    require(canonical)
    require(migration)

    workflow = canonical.read_text(encoding="utf-8")
    if "com.ksp.cryptobot" not in workflow or "apply_milestone6.py" not in workflow:
        fail("Canonical workflow identity/migration marker differs from the audited baseline; refusing blind patch.")

    # 1) Authoritative v4 governance overlay.
    safe_path = repo / ".cts-v4-migration/app/src/main/java/com/ksp/cryptobot/governance/RiskBudgetAndSafeMode.kt"
    require(safe_path)
    write_text(safe_path, SAFE_MODE_SOURCE)

    # Safe mode is an entry brake, not an exit brake. Preserve SELL eligibility
    # so risk reduction/protective exits are not suppressed by PAPER_ONLY/PAUSED.
    guard_path = repo / ".cts-v4-migration/app/src/main/java/com/ksp/cryptobot/execution/ExecutionGuard.kt"
    require(guard_path)
    guard = guard_path.read_text(encoding="utf-8")
    if "internal fun productionSafeModeBlocks(" not in guard:
        guard = replace_once(
            guard,
            "class ExecutionGuard(private val dao: AppDao) {\n",
            '''internal fun productionSafeModeBlocks(mode: BotMode, action: SignalAction, blockLiveEntries: Boolean): Boolean =
    mode != BotMode.PAPER && blockLiveEntries && action in setOf(SignalAction.BUY, SignalAction.SMALL_BUY)

class ExecutionGuard(private val dao: AppDao) {
''',
            "ExecutionGuard safe-mode helper"
        )
    guard = replace_once(
        guard,
        '''        if (settings.mode != BotMode.PAPER && production.blockLiveEntries) {
            return false to "Production intelligence blocked live entries: safeMode=${production.safeModeLevel}; ${production.lastReason.take(500)}"
        }
''',
        '''        if (productionSafeModeBlocks(settings.mode, decision.finalAction, production.blockLiveEntries)) {
            return false to "Production intelligence blocked new live entry: safeMode=${production.safeModeLevel}; ${production.lastReason.take(500)}"
        }
''',
        "ExecutionGuard entry-only safe mode"
    )
    guard_path.write_text(guard, encoding="utf-8")
    patch_entry_governors(repo)

    # 2) Base news health/cooldown truth. The v4 migration currently does not
    # overwrite the base news package, so this survives the migration step.
    write_text(repo / "app/src/main/java/com/ksp/cryptobot/news/NewsProviderHealth.kt", NEWS_HEALTH_SOURCE)
    write_text(repo / "app/src/main/java/com/ksp/cryptobot/news/NewsClient.kt", NEWS_CLIENT_SOURCE)

    # 3) Wire health/cooldown + previously orphaned CryptoPanic key into the
    # actual scan path (fetchNewsForSymbol), not only CompositeNewsClient.
    controller_path = repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt"
    require(controller_path)
    text = controller_path.read_text(encoding="utf-8")
    if "import com.ksp.cryptobot.news.NewsProviderHealthRegistry" not in text:
        text = replace_once(
            text,
            "import com.ksp.cryptobot.news.NoopNewsClient\n",
            "import com.ksp.cryptobot.news.NoopNewsClient\nimport com.ksp.cryptobot.news.NewsProviderHealthRegistry\n",
            "BotController news-health import"
        )

    old_status = '''            val marketauxKey = !settingsStore.marketauxApiKey().isNullOrBlank()\n            val newsDataKey = !settingsStore.newsDataApiKey().isNullOrBlank()\n            val gNewsKey = !settingsStore.gNewsApiKey().isNullOrBlank()\n            val guardianKey = !settingsStore.guardianApiKey().isNullOrBlank()\n            updateStatus("News AI enabled: GDELT + RSS + ${if (marketauxKey) "Marketaux" else "Marketaux(no key)"} + ${if (newsDataKey) "NewsData.io" else "NewsData.io(no key)"} + ${if (gNewsKey) "GNews" else "GNews(no key)"} + ${if (guardianKey) "Guardian" else "Guardian(no key)"} + $newsApiKeyCount NewsAPI key(s).", "INFO")\n'''
    new_status = '''            val cryptoPanicKey = !settingsStore.cryptoPanicApiKey().isNullOrBlank()\n            val marketauxKey = !settingsStore.marketauxApiKey().isNullOrBlank()\n            val newsDataKey = !settingsStore.newsDataApiKey().isNullOrBlank()\n            val gNewsKey = !settingsStore.gNewsApiKey().isNullOrBlank()\n            val guardianKey = !settingsStore.guardianApiKey().isNullOrBlank()\n            updateStatus("News AI enabled: GDELT + RSS + ${if (cryptoPanicKey) "CryptoPanic" else "CryptoPanic(no key)"} + ${if (marketauxKey) "Marketaux" else "Marketaux(no key)"} + ${if (newsDataKey) "NewsData.io" else "NewsData.io(no key)"} + ${if (gNewsKey) "GNews" else "GNews(no key)"} + ${if (guardianKey) "Guardian" else "Guardian(no key)"} + $newsApiKeyCount NewsAPI key(s).", "INFO")\n'''
    text = replace_once(text, old_status, new_status, "BotController news status")

    rss_anchor = '''        providers += "GDELT" to GdeltNewsClient()\n        providers += "RSS" to RssFeedNewsClient()\n\n        settingsStore.marketauxApiKey()?.trim()?.takeIf { it.isNotBlank() }?.let {\n'''
    rss_repl = '''        providers += "GDELT" to GdeltNewsClient()\n        providers += "RSS" to RssFeedNewsClient()\n\n        settingsStore.cryptoPanicApiKey()?.trim()?.takeIf { it.isNotBlank() }?.let {\n            providers += "CryptoPanic" to CryptoPanicNewsClient(it)\n        } ?: updateStatus("[$symbol] CryptoPanic skipped: no API key saved.", "WARN")\n\n        settingsStore.marketauxApiKey()?.trim()?.takeIf { it.isNotBlank() }?.let {\n'''
    text = replace_once(text, rss_anchor, rss_repl, "BotController CryptoPanic scan provider")

    old_loop = '''        val all = mutableListOf<NewsArticle>()\n        providers.forEach { (name, provider) ->\n            runCatching { provider.latestCryptoNews(symbol) }\n                .onSuccess { articles ->\n                    all += articles\n                    updateStatus("[$symbol] $name API call complete: articles=${articles.size}.", if (articles.isEmpty()) "WARN" else "INFO")\n                }\n                .onFailure { error ->\n                    updateStatus("[$symbol] $name API call failed: ${error.message}", "WARN")\n                }\n        }\n'''
    new_loop = '''        val all = mutableListOf<NewsArticle>()\n        providers.forEach { (name, provider) ->\n            if (!NewsProviderHealthRegistry.shouldAttempt(name)) {\n                val health = NewsProviderHealthRegistry.healthFor(name)\n                updateStatus("[$symbol] $name skipped during local retry cooldown until ${health?.cooldownUntilEpochMs ?: 0L}. Last error=${health?.lastError.orEmpty().take(120)}", "WARN")\n                return@forEach\n            }\n            NewsProviderHealthRegistry.recordAttempt(name)\n            runCatching { provider.latestCryptoNews(symbol) }\n                .onSuccess { articles ->\n                    NewsProviderHealthRegistry.recordSuccess(name, articles.size)\n                    all += articles\n                    updateStatus("[$symbol] $name API call complete: articles=${articles.size}.", if (articles.isEmpty()) "WARN" else "INFO")\n                }\n                .onFailure { error ->\n                    NewsProviderHealthRegistry.recordFailure(name, error)\n                    val health = NewsProviderHealthRegistry.healthFor(name)\n                    updateStatus("[$symbol] $name API call failed: ${error.message}. Local retry cooldownUntil=${health?.cooldownUntilEpochMs ?: 0L}.", "WARN")\n                }\n        }\n'''
    text = replace_once(text, old_loop, new_loop, "BotController provider cooldown loop")

    create_anchor = '''        val providers = mutableListOf<NewsClient>(\n            GdeltNewsClient(),\n            RssFeedNewsClient()\n        )\n        settingsStore.marketauxApiKey()?.takeIf { it.isNotBlank() }?.let { providers += MarketauxNewsClient(it) }\n'''
    create_repl = '''        val providers = mutableListOf<NewsClient>(\n            GdeltNewsClient(),\n            RssFeedNewsClient()\n        )\n        settingsStore.cryptoPanicApiKey()?.takeIf { it.isNotBlank() }?.let { providers += CryptoPanicNewsClient(it) }\n        settingsStore.marketauxApiKey()?.takeIf { it.isNotBlank() }?.let { providers += MarketauxNewsClient(it) }\n'''
    text = replace_once(text, create_anchor, create_repl, "BotController Composite CryptoPanic provider")
    controller_path.write_text(text, encoding="utf-8")

    # 4) UI: CryptoPanic was passed/saved but never rendered. Also make the
    # visible provider list truthful.
    main_path = repo / "app/src/main/java/com/ksp/cryptobot/MainActivity.kt"
    require(main_path)
    main = main_path.read_text(encoding="utf-8")
    main = replace_once(
        main,
        '''                OutlinedTextField(value = newsKey, onValueChange = onNewsKey, label = { Text("NewsAPI.org key(s), comma-separated") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())\n                OutlinedTextField(value = marketauxKey, onValueChange = onMarketauxKey, label = { Text("Marketaux API key") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())\n''',
        '''                OutlinedTextField(value = newsKey, onValueChange = onNewsKey, label = { Text("NewsAPI.org key(s), comma-separated") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())\n                OutlinedTextField(value = cryptoPanicKey, onValueChange = onCryptoPanicKey, label = { Text("CryptoPanic API key") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())\n                OutlinedTextField(value = marketauxKey, onValueChange = onMarketauxKey, label = { Text("Marketaux API key") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())\n''',
        "MainActivity CryptoPanic field"
    )
    main = replace_once(
        main,
        '                Text("News stack: GDELT + RSS + Marketaux + NewsData.io + GNews + Guardian + NewsAPI.org.", color = Muted)\n',
        '                Text("News stack: GDELT + RSS + CryptoPanic + Marketaux + NewsData.io + GNews + Guardian + NewsAPI.org. GDELT/RSS need no API key.", color = Muted)\n',
        "MainActivity news stack truth"
    )
    old_persist = '''    fun persistSettings(newSettings: BotSettings) {\n        settings = newSettings\n        store.save(newSettings)\n        status = "Settings saved"\n    }\n'''
    new_persist = '''    fun persistSettings(newSettings: BotSettings) {\n        val verified = store.save(newSettings)\n        val effective = store.load()\n        settings = effective\n        status = if (verified && effective == newSettings) "Settings saved and verified" else "Settings save verification failed — check v4 Effective Settings Truth"\n        statusStore.write(status, if (verified && effective == newSettings) "INFO" else "ERROR")\n    }\n'''
    main = replace_once(main, old_persist, new_persist, "MainActivity settings save/reload truth")
    main_path.write_text(main, encoding="utf-8")

    # 5) Persisted settings save verification. Returning Boolean is source
    # compatible with existing callers that ignore the return value.
    settings_path = repo / "app/src/main/java/com/ksp/cryptobot/settings/AppSettingsStore.kt"
    require(settings_path)
    store = settings_path.read_text(encoding="utf-8")
    if "data class SettingsSaveVerification(" not in store:
        store = replace_once(
            store,
            "import java.math.BigDecimal\n\nclass AppSettingsStore(context: Context) {\n",
            '''import java.math.BigDecimal\n\ndata class SettingsSaveVerification(\n    val committed: Boolean,\n    val exactMatch: Boolean,\n    val timestampEpochMs: Long,\n    val effectiveMode: String\n)\n\nclass AppSettingsStore(context: Context) {\n''',
            "AppSettingsStore verification model"
        )
    store = replace_once(
        store,
        "    fun save(settings: BotSettings) {\n        prefs.edit()\n",
        "    fun save(settings: BotSettings): Boolean {\n        val committed = prefs.edit()\n",
        "AppSettingsStore committed return"
    )
    tail_old = '''            .putInt("tax_export_year", settings.taxExportYear)\n            .commit()\n    }\n\n    fun saveBinanceKeys(apiKey: String, secretKey: String) {\n'''
    tail_new = '''            .putInt("tax_export_year", settings.taxExportYear)\n            .commit()\n        val effective = runCatching { load() }.getOrNull()\n        val exactMatch = committed && effective == settings\n        val saveEpochMs = System.currentTimeMillis()\n        prefs.edit()\n            .putBoolean("_last_settings_save_committed", committed)\n            .putBoolean("_last_settings_save_matches", exactMatch)\n            .putLong("_last_settings_save_epoch_ms", saveEpochMs)\n            .putString("_last_settings_save_effective_mode", effective?.mode?.name ?: "LOAD_FAILED")\n            .commit()\n        return exactMatch\n    }\n\n    fun lastSaveVerification(): SettingsSaveVerification = SettingsSaveVerification(\n        committed = prefs.getBoolean("_last_settings_save_committed", false),\n        exactMatch = prefs.getBoolean("_last_settings_save_matches", false),\n        timestampEpochMs = prefs.getLong("_last_settings_save_epoch_ms", 0L),\n        effectiveMode = prefs.getString("_last_settings_save_effective_mode", "UNKNOWN") ?: "UNKNOWN"\n    )\n\n    fun saveBinanceKeys(apiKey: String, secretKey: String) {\n'''
    store = replace_once(store, tail_old, tail_new, "AppSettingsStore save verification tail")
    settings_path.write_text(store, encoding="utf-8")

    # 6) Completed outcomes only. BUY ledger rows are entry evidence, not
    # completed P/L samples; mode separation now actually affects sampling.
    learning_path = repo / "app/src/main/java/com/ksp/cryptobot/learning/TrueSelfLearningEngine.kt"
    require(learning_path)
    learning = learning_path.read_text(encoding="utf-8")
    if "import com.ksp.cryptobot.core.BotMode" not in learning:
        learning = replace_once(
            learning,
            "import com.ksp.cryptobot.core.AiDecision\n",
            "import com.ksp.cryptobot.core.AiDecision\nimport com.ksp.cryptobot.core.BotMode\n",
            "learning BotMode import"
        )
    if "internal fun completedOutcomeTradesForLearning(" not in learning:
        class_anchor = "class TrueSelfLearningEngine {\n"
        helper = '''internal fun learningHistoryForMode(trades: List<TradeEntity>, settings: BotSettings): List<TradeEntity> {\n    if (!settings.selfLearningPaperAndLiveSeparated) return trades\n    val paperMode = settings.mode == BotMode.PAPER\n    return trades.filter { it.paper == paperMode }\n}\n\ninternal fun completedOutcomeTradesForLearning(trades: List<TradeEntity>, settings: BotSettings): List<TradeEntity> =\n    learningHistoryForMode(trades, settings).filter { it.side.equals(OrderSide.SELL.name, ignoreCase = true) }\n\nclass TrueSelfLearningEngine {\n'''
        learning = replace_once(learning, class_anchor, helper, "learning completed-outcome helpers")

    refresh_old = '''        val trades = dao.allTradesSnapshot().take(settings.selfLearningLookbackTrades.coerceAtLeast(20))\n        val now = System.currentTimeMillis()\n        val symbolProfiles = trades.groupBy { it.symbol.uppercase() }.map { (symbol, rows) ->\n            buildSymbolProfile(symbol, rows, settings, now).also { profile ->\n                dao.upsertLearnedSymbolProfile(profile)\n                dao.insertSelfLearningAudit(SelfLearningAuditEntity(timestampEpochMs = now, eventType = "PROFILE_UPDATE", symbol = symbol, message = profile.explanation))\n            }\n        }\n\n        val strategyProfiles = trades.groupBy { strategyKeyFromTrade(it) }.map { (strategy, rows) ->\n            buildStrategyProfile(strategy, rows, settings, now).also { profile -> dao.upsertLearnedStrategyProfile(profile) }\n        }\n\n        val holdProfiles = trades.groupBy { it.symbol.uppercase() }.map { (symbol, rows) ->\n            buildHoldProfile(symbol, rows, settings, now).also { profile ->\n                dao.upsertLearnedHoldProfile(profile)\n                dao.insertSelfLearningAudit(SelfLearningAuditEntity(timestampEpochMs = now, eventType = "HOLD_PROFILE_UPDATE", symbol = symbol, message = profile.explanation))\n            }\n        }\n\n        val audit = dao.selfLearningAudit(40)\n        val liveCount = trades.count { !it.paper }\n        val paperCount = trades.count { it.paper }\n        val summary = "Learning refreshed: symbols=${symbolProfiles.size}, strategies=${strategyProfiles.size}, holdProfiles=${holdProfiles.size}, trades=${trades.size}, live=$liveCount, paper=$paperCount. Min sample=${settings.selfLearningMinSamples}."\n'''
    refresh_new = '''        val allTrades = dao.allTradesSnapshot().take(settings.selfLearningLookbackTrades.coerceAtLeast(20))\n        val learningHistory = learningHistoryForMode(allTrades, settings)\n        val completedOutcomes = completedOutcomeTradesForLearning(allTrades, settings)\n        val now = System.currentTimeMillis()\n        val symbolProfiles = completedOutcomes.groupBy { it.symbol.uppercase() }.map { (symbol, rows) ->\n            buildSymbolProfile(symbol, rows, settings, now).also { profile ->\n                dao.upsertLearnedSymbolProfile(profile)\n                dao.insertSelfLearningAudit(SelfLearningAuditEntity(timestampEpochMs = now, eventType = "PROFILE_UPDATE", symbol = symbol, message = profile.explanation))\n            }\n        }\n\n        val strategyProfiles = completedOutcomes.groupBy { strategyKeyFromTrade(it) }.map { (strategy, rows) ->\n            buildStrategyProfile(strategy, rows, settings, now).also { profile -> dao.upsertLearnedStrategyProfile(profile) }\n        }\n\n        val holdProfiles = learningHistory.groupBy { it.symbol.uppercase() }.map { (symbol, rows) ->\n            buildHoldProfile(symbol, rows, settings, now).also { profile ->\n                dao.upsertLearnedHoldProfile(profile)\n                dao.insertSelfLearningAudit(SelfLearningAuditEntity(timestampEpochMs = now, eventType = "HOLD_PROFILE_UPDATE", symbol = symbol, message = profile.explanation))\n            }\n        }\n\n        val audit = dao.selfLearningAudit(40)\n        val liveCount = completedOutcomes.count { !it.paper }\n        val paperCount = completedOutcomes.count { it.paper }\n        val separation = if (settings.selfLearningPaperAndLiveSeparated) "separated:${if (settings.mode == BotMode.PAPER) "PAPER" else "LIVE"}" else "combined"\n        val summary = "Learning refreshed: symbols=${symbolProfiles.size}, strategies=${strategyProfiles.size}, holdProfiles=${holdProfiles.size}, completedOutcomes=${completedOutcomes.size}, historyRows=${learningHistory.size}, liveOutcomes=$liveCount, paperOutcomes=$paperCount, mode=$separation. Min sample=${settings.selfLearningMinSamples}."\n'''
    learning = replace_once(learning, refresh_old, refresh_new, "completed-outcome refresh")
    learning_path.write_text(learning, encoding="utf-8")

    # 7) Add a complete read-only effective-settings inspector to the v4 control
    # center. This exposes every persisted BotSettings field without leaking secure
    # API secrets (secrets are kept in SecureSettingsStore, not BotSettings).
    control_path = repo / ".cts-v4-migration/app/src/main/java/com/ksp/cryptobot/ui/V4ControlCenterScreen.kt"
    require(control_path)
    control = control_path.read_text(encoding="utf-8")
    if "import com.ksp.cryptobot.settings.AppSettingsStore" not in control:
        control = replace_once(
            control,
            "import com.ksp.cryptobot.research.ResearchSettingsStore\n",
            "import com.ksp.cryptobot.research.ResearchSettingsStore\nimport com.ksp.cryptobot.settings.AppSettingsStore\n",
            "V4Control settings-store import"
        )
    control = replace_once(
        control,
        'private enum class V4Panel(val label: String) { OVERVIEW("Overview"), CLOUDSHARE("CloudShare"), RESEARCH("Research"), RECOVERY("Recovery") }',
        'private enum class V4Panel(val label: String) { OVERVIEW("Overview"), SETTINGS("Settings Truth"), CLOUDSHARE("CloudShare"), RESEARCH("Research"), RECOVERY("Recovery") }',
        "V4Control Settings Truth tab"
    )
    control = replace_once(
        control,
        "        when (panel) {\n            V4Panel.OVERVIEW -> V4OverviewPanel()\n            V4Panel.CLOUDSHARE -> CloudShareScreen()\n",
        "        when (panel) {\n            V4Panel.OVERVIEW -> V4OverviewPanel()\n            V4Panel.SETTINGS -> V4SettingsTruthPanel()\n            V4Panel.CLOUDSHARE -> CloudShareScreen()\n",
        "V4Control Settings Truth routing"
    )
    if "private fun V4SettingsTruthPanel()" not in control:
        panel_source = r'''
@Composable
private fun V4SettingsTruthPanel() {
    val context = LocalContext.current
    val store = remember { AppSettingsStore(context.applicationContext) }
    var effective by remember { mutableStateOf(store.load()) }
    var saveTruth by remember { mutableStateOf(store.lastSaveVerification()) }
    val fields = remember(effective) {
        effective.toString()
            .removePrefix("BotSettings(")
            .removeSuffix(")")
            .split(Regex(", (?=[A-Za-z][A-Za-z0-9]*=)"))
            .filter { it.isNotBlank() }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Effective Settings Truth", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Read-only persisted BotSettings after save/reload. This panel intentionally does not show API keys or other encrypted secrets.")
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Last save verification", fontWeight = FontWeight.Bold)
                Text("commit=${saveTruth.committed} • exact reload=${saveTruth.exactMatch} • epochMs=${saveTruth.timestampEpochMs} • mode=${saveTruth.effectiveMode}")
            }
        }
        Button(onClick = {
            effective = store.load()
            saveTruth = store.lastSaveVerification()
        }) { Text("Reload persisted settings") }
        Text("All effective BotSettings fields (${fields.size})", fontWeight = FontWeight.Bold)
        fields.forEach { field ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Text(field, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
}

'''
        control = replace_once(
            control,
            "@Composable\nprivate fun V4OverviewPanel() {",
            panel_source + "@Composable\nprivate fun V4OverviewPanel() {",
            "V4Control Settings Truth panel"
        )
    control_path.write_text(control, encoding="utf-8")

    # 8) Authoritative v4 verifier overlay: complete persisted settings truth,
    # provider health, and stage-by-stage runtime evidence.
    verifier_path = repo / ".cts-v4-migration/app/src/main/java/com/ksp/cryptobot/release/V4SystemVerifier.kt"
    require(verifier_path)
    write_text(verifier_path, V4_VERIFIER_SOURCE)

    # 9) Regression tests. These compile after apply_milestone6.py adds governance.
    write_text(repo / "app/src/test/java/com/ksp/cryptobot/governance/SafeModeControllerTest.kt", SAFE_MODE_TEST)
    write_text(repo / "app/src/test/java/com/ksp/cryptobot/governance/ProductionEntryGovernorsTest.kt", GOVERNANCE_ENTRY_TEST)
    write_text(repo / "app/src/test/java/com/ksp/cryptobot/execution/ExecutionGuardSafeModeTest.kt", EXECUTION_GUARD_TEST)
    write_text(repo / "app/src/test/java/com/ksp/cryptobot/news/NewsProviderHealthRegistryTest.kt", NEWS_HEALTH_TEST)
    write_text(repo / "app/src/test/java/com/ksp/cryptobot/learning/CompletedLearningOutcomeTest.kt", LEARNING_OUTCOME_TEST)

    print("[CTS diagnostics fix] Applied successfully.")
    print("Patched authoritative migration overlay: governance safe mode + v4 verifier")
    print("Patched base: news health/cooldown, CryptoPanic wiring/UI, settings save/reload truth, completed-outcome learning")
    print("Added regression tests: safe-mode unlatching, entry-only M3/M4 governance, news cooldown, completed-outcome sampling")
    print("Next: GitHub Actions will validate, compile, test, assemble and verify the canonical APK.")


def main() -> None:
    repo = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd().resolve()
    patch(repo)


if __name__ == "__main__":
    main()
