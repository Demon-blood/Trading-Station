#!/usr/bin/env python3
from pathlib import Path
import os
import sys

PAYLOAD_FILES = [
    "app/src/main/java/com/ksp/cryptobot/execution/NetProfitCostOptimizer.kt",
    "app/src/test/java/com/ksp/cryptobot/execution/NetProfitCostOptimizerM20Test.kt",
]

def fail(msg):
    raise SystemExit("ERROR | " + msg)

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        fail(f"{label}: expected one match, got {count}")
    return text.replace(old, new, 1)

def main():
    print("INFO | M20 applier revision v1")
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    if not (repo / ".git").exists():
        fail("Not a git checkout")

    prerequisite = repo / "tools/verify_m19_learning_governance.py"
    if not prerequisite.exists():
        fail("M19 prerequisite missing from main")

    dirty = os.popen(f'cd "{repo}" && git status --porcelain -- app').read().strip()
    if dirty:
        fail("Refusing to patch dirty app tree:\n" + dirty)

    payload = Path(__file__).resolve().parent / "m20_payload"
    for rel in PAYLOAD_FILES:
        src = payload / rel
        dst = repo / rel
        if not src.exists():
            fail(f"M20 payload missing: {rel}")
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_text(src.read_text(encoding="utf-8").rstrip() + "\n", encoding="utf-8")
        copied = dst.read_bytes()
        if copied.endswith(b"\\n"):
            fail(f"M20 payload copy produced literal backslash-n EOF: {rel}")
        if not copied.endswith(b"\n"):
            fail(f"M20 payload copy missing real newline EOF: {rel}")
        print("WRITE |", rel)

    p = repo / "app/src/main/java/com/ksp/cryptobot/execution/SmartOrderLifecycleManager.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        '''import java.time.Instant
import kotlin.math.roundToLong
''',
        '''import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong
''',
        "M20 calibration runtime import"
    )
    t = replace_once(
        t,
        '''data class ExecutionCalibrationSnapshot(
    val samples: Int,
    val meanFillSeconds: Double,
    val meanSlippageBps: Double,
    val totalAmendments: Long,
    val totalCancels: Long
) {
    val amendmentsPerCompletedFill: Double
        get() = if (samples <= 0) 0.0 else totalAmendments.toDouble() / samples.toDouble()
}

object SmartOrderLifecyclePolicy {
''',
        '''data class ExecutionCalibrationSnapshot(
    val samples: Int,
    val meanFillSeconds: Double,
    val meanSlippageBps: Double,
    val totalAmendments: Long,
    val totalCancels: Long
) {
    val amendmentsPerCompletedFill: Double
        get() = if (samples <= 0) 0.0 else totalAmendments.toDouble() / samples.toDouble()
}

object ExecutionCalibrationRuntime {
    private val snapshots = ConcurrentHashMap<String, ExecutionCalibrationSnapshot>()

    private fun key(symbol: String): String =
        symbol.uppercase().replace("/", "").replace("-", "").replace("_", "")

    fun publish(symbol: String, snapshot: ExecutionCalibrationSnapshot) {
        snapshots[key(symbol)] = snapshot
    }

    fun snapshot(symbol: String): ExecutionCalibrationSnapshot? = snapshots[key(symbol)]
    fun all(): Map<String, ExecutionCalibrationSnapshot> = snapshots.toMap()
    fun clearAll() = snapshots.clear()
}

object SmartOrderLifecyclePolicy {
''',
        "M20 calibration runtime"
    )
    t = replace_once(
        t,
        '''    fun calibration(symbol: String): ExecutionCalibrationSnapshot {
        val key = symbolKey(symbol)
        return ExecutionCalibrationSnapshot(
            samples = prefs.getInt("${key}_samples", 0),
            meanFillSeconds = prefs.getString("${key}_mean_fill_sec", "0")?.toDoubleOrNull() ?: 0.0,
            meanSlippageBps = prefs.getString("${key}_mean_slippage_bps", "0")?.toDoubleOrNull() ?: 0.0,
            totalAmendments = prefs.getLong("${key}_amends", 0L),
            totalCancels = prefs.getLong("${key}_cancels", 0L)
        )
    }
''',
        '''    fun calibration(symbol: String): ExecutionCalibrationSnapshot {
        val key = symbolKey(symbol)
        val snapshot = ExecutionCalibrationSnapshot(
            samples = prefs.getInt("${key}_samples", 0),
            meanFillSeconds = prefs.getString("${key}_mean_fill_sec", "0")?.toDoubleOrNull() ?: 0.0,
            meanSlippageBps = prefs.getString("${key}_mean_slippage_bps", "0")?.toDoubleOrNull() ?: 0.0,
            totalAmendments = prefs.getLong("${key}_amends", 0L),
            totalCancels = prefs.getLong("${key}_cancels", 0L)
        )
        ExecutionCalibrationRuntime.publish(symbol, snapshot)
        return snapshot
    }
''',
        "M20 calibration publication"
    )
    p.write_text(t.rstrip() + "\n", encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    p = repo / "app/src/main/java/com/ksp/cryptobot/execution/AdvancedExecutionCoordinator.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        '''    private val orderTypeOptimizer = OrderTypeOptimizer()
    private val tradeEconomics = TradeEconomicsEngine()
    private val aiValueAttribution = AiValueAttributionEngine(governanceDao)
''',
        '''    private val orderTypeOptimizer = OrderTypeOptimizer()
    private val tradeEconomics = TradeEconomicsEngine()
    private val netProfitOptimizer = NetProfitCostOptimizer()
    private val aiValueAttribution = AiValueAttributionEngine(governanceDao)
''',
        "M20 optimizer field"
    )

    t = replace_once(
        t,
        '''        if (!economics.allowed) {
            val reason = "M5 trade economics blocked entry: ${economics.reason}"
            return AdvancedEntryPlan(false, finalQuote, finalOrderType, finalLimitOrTrigger, BigDecimal.ZERO, protection.level, reason)
        }

        val combined = finalQuote.divide(requestedQuote, 6, RoundingMode.HALF_UP).coerceIn(BigDecimal.ZERO, BigDecimal.ONE)
''',
        '''        if (!economics.allowed) {
            val reason = "M5 trade economics blocked entry: ${economics.reason}"
            return AdvancedEntryPlan(false, finalQuote, finalOrderType, finalLimitOrTrigger, BigDecimal.ZERO, protection.level, reason)
        }

        val m20ExecutionQuality = runCatching {
            governanceDao.executionQuality(decision.symbol, "BUY", mode, 100)
        }.getOrDefault(emptyList())
        val m20AdvancedExecution = runCatching {
            governanceDao.recentAdvancedExecution(500)
        }.getOrDefault(emptyList())
        val m20GovernanceEvents = runCatching {
            governanceDao.recentEventsForSymbol(decision.symbol, 100)
        }.getOrDefault(emptyList())
        val netProfit = netProfitOptimizer.evaluate(
            NetProfitCostInput(
                economics = economics,
                recentTrades = trades,
                executionQuality = m20ExecutionQuality,
                advancedExecution = m20AdvancedExecution,
                governanceEvents = m20GovernanceEvents,
                calibration = ExecutionCalibrationRuntime.snapshot(decision.symbol),
                maxTradesPerDay = settings.maxTradesPerDay,
                currentMode = mode,
                explicitInfrastructureCostQuote = BigDecimal.ZERO
            )
        )
        record(
            "net_profit_optimizer",
            decision.symbol,
            settings,
            mode,
            requestedQuote,
            finalQuote,
            netProfit.adjustedNetExpectedValueRate,
            finalOrderType.name,
            if (netProfit.allowed) "positive_adjusted_net_ev" else "insufficient_adjusted_net_ev",
            sizeBand(requestedQuote),
            "",
            if (netProfit.allowed) "normal" else "blocked",
            !netProfit.allowed,
            netProfit.reason,
            if (netProfit.allowed) "INFO" else "WARN"
        )
        if (!netProfit.allowed) {
            val reason = "M20 net-profit optimizer blocked entry: ${netProfit.reason}"
            return AdvancedEntryPlan(false, finalQuote, finalOrderType, finalLimitOrTrigger, BigDecimal.ZERO, protection.level, reason)
        }

        val combined = finalQuote.divide(requestedQuote, 6, RoundingMode.HALF_UP).coerceIn(BigDecimal.ZERO, BigDecimal.ONE)
''',
        "M20 downstream M5 integration"
    )

    t = replace_once(
        t,
        '''            finalOrderType.name, if (directive?.preferredOrderType != null) "handoff_source_order" else optimizedOrder.reasonCategory, sizeBand(requestedQuote), "", "normal", false, optimizedOrder.reason + " | " + economics.reason, "INFO")
''',
        '''            finalOrderType.name, if (directive?.preferredOrderType != null) "handoff_source_order" else optimizedOrder.reasonCategory, sizeBand(requestedQuote), "", "normal", false, optimizedOrder.reason + " | " + economics.reason + " | " + netProfit.reason, "INFO")
''',
        "M20 order-type explanation"
    )
    t = replace_once(
        t,
        '''        val reason = "advanced entry plan: requested=${requestedQuote.s2()}, researchCap=${researchCappedQuote.s2()}, final=${finalQuote.s2()}, combined×${combined.setScale(3, RoundingMode.HALF_UP)}, protection=${protection.level}, order=$finalOrderType.$handoff ${allocation.reason} | ${liquidity.reason} | ${optimizedOrder.reason} | ${economics.reason}"
''',
        '''        val reason = "advanced entry plan: requested=${requestedQuote.s2()}, researchCap=${researchCappedQuote.s2()}, final=${finalQuote.s2()}, combined×${combined.setScale(3, RoundingMode.HALF_UP)}, protection=${protection.level}, order=$finalOrderType.$handoff ${allocation.reason} | ${liquidity.reason} | ${optimizedOrder.reason} | ${economics.reason} | ${netProfit.reason}"
''',
        "M20 final plan explanation"
    )
    p.write_text(t.rstrip() + "\n", encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    print("PASS | M20 controlled app diff.")

if __name__ == "__main__":
    main()
