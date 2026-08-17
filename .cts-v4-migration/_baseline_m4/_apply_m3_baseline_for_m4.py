#!/usr/bin/env python3
"""Apply cumulative Crypto TradeStation Android v4 Milestone 3 to v3.2.5, M1, or M2."""
from __future__ import annotations
import re, shutil, sys
from pathlib import Path
HERE = Path(__file__).resolve().parent

def fail(msg: str) -> None: raise SystemExit(msg)

def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1: fail(f"Cannot patch {label}: expected exactly one match, found {count}.")
    return text.replace(old, new, 1)

def copy_overlay(src: Path) -> None:
    root = HERE / "app/src/main/java/com/ksp/cryptobot"
    for folder in ("cloudshare", "data", "ui", "intelligence", "governance", "execution"):
        source = root / folder
        if not source.exists(): continue
        target = src / folder; target.mkdir(parents=True, exist_ok=True)
        for f in source.glob("*.kt"): shutil.copy2(f, target / f.name)

def patch_service(service: Path) -> None:
    text = service.read_text(encoding="utf-8")
    # Cumulative M1/M2 CloudShare hook.
    if "CloudShareSyncEngine" not in text:
        text = replace_once(text, "import com.ksp.cryptobot.core.BotMode\n", "import com.ksp.cryptobot.core.BotMode\nimport com.ksp.cryptobot.cloudshare.CloudShareSyncEngine\n", "service cloud import")
        text = replace_once(text, "    private lateinit var statusStore: BotStatusStore\n", "    private lateinit var statusStore: BotStatusStore\n    private lateinit var cloudShareSync: CloudShareSyncEngine\n", "service cloud field")
        text = replace_once(text, "        statusStore = BotStatusStore(applicationContext)\n        createChannel()\n", "        statusStore = BotStatusStore(applicationContext)\n        cloudShareSync = CloudShareSyncEngine(applicationContext)\n        createChannel()\n", "service cloud init")
        text = replace_once(text, "                try {\n                    controller.processRemoteCommands(current)\n", '''                try {\n                    val cloud = cloudShareSync.syncIfDue()\n                    if (cloud.error.isNotBlank()) {\n                        statusStore.write("CloudShare sync deferred: ${cloud.error}", "WARN")\n                    } else if (cloud.uploaded + cloud.duplicates + cloud.rejected + cloud.downloaded + cloud.backfilled + cloud.aggregatesQueued > 0) {\n                        statusStore.write("CloudShare sync: upload=${cloud.uploaded}, duplicate=${cloud.duplicates}, rejected=${cloud.rejected}, download=${cloud.downloaded}, aggregate=${cloud.aggregatesQueued}, backfill=${cloud.backfilled}, collective=${cloud.collectiveOutcomeRows}", "INFO")\n                    }\n                    controller.processRemoteCommands(current)\n''', "service cloud cycle")
    # M3 watchdog/crash recovery monitor.
    if "ProductionIntelligenceServiceMonitor" not in text:
        text = replace_once(text, "import com.ksp.cryptobot.cloudshare.CloudShareSyncEngine\n", "import com.ksp.cryptobot.cloudshare.CloudShareSyncEngine\nimport com.ksp.cryptobot.governance.ProductionIntelligenceServiceMonitor\nimport com.ksp.cryptobot.data.AppDatabase\n", "service production imports")
        text = replace_once(text, "    private lateinit var cloudShareSync: CloudShareSyncEngine\n", "    private lateinit var cloudShareSync: CloudShareSyncEngine\n    private lateinit var productionMonitor: ProductionIntelligenceServiceMonitor\n", "service production field")
        text = replace_once(text, "        cloudShareSync = CloudShareSyncEngine(applicationContext)\n        createChannel()\n", "        cloudShareSync = CloudShareSyncEngine(applicationContext)\n        productionMonitor = ProductionIntelligenceServiceMonitor(AppDatabase.get(applicationContext).governanceDao())\n        createChannel()\n", "service production init")
        text = replace_once(text, "        scope.launch {\n            val startSettings = settingsStore.load()\n", "        scope.launch {\n            productionMonitor.onServiceStart()\n            val startSettings = settingsStore.load()\n", "service recovery start")
        text = replace_once(text, "                try {\n                    val cloud = cloudShareSync.syncIfDue()\n", "                try {\n                    productionMonitor.heartbeat()\n                    val cloud = cloudShareSync.syncIfDue()\n", "service heartbeat")
        text = replace_once(text, "                } catch (error: Exception) {\n                    statusStore.write(\"Service cycle failed: ${error.message}\", \"ERROR\")\n", "                } catch (error: Exception) {\n                    productionMonitor.recordLoopError(error.message ?: error.javaClass.simpleName)\n                    statusStore.write(\"Service cycle failed: ${error.message}\", \"ERROR\")\n", "service error monitor")
    service.write_text(text, encoding="utf-8")

def patch_strategy(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if "CloudShareCollectiveCache" not in text:
        text = replace_once(text, "import com.ksp.cryptobot.core.*\n", "import com.ksp.cryptobot.core.*\nimport com.ksp.cryptobot.cloudshare.CloudShareCollectiveCache\n", "strategy import")
    old = '        return candidates.maxByOrNull { it.score } ?: StrategyCandidate(StrategyMode.AUTO, 0, SignalAction.WAIT, "No strategy candidate available.", BigDecimal.ZERO, BigDecimal.ZERO)\n'
    if "collectiveTieBreak" not in text:
        new = '''        val selected = candidates.maxByOrNull { candidate ->\n            val collectiveTieBreak = CloudShareCollectiveCache.score(ticker.symbol, candidate.mode.name, regime.regime.name, "15m").adjustment.coerceIn(-2, 2)\n            candidate.score + collectiveTieBreak\n        } ?: return StrategyCandidate(StrategyMode.AUTO, 0, SignalAction.WAIT, "No strategy candidate available.", BigDecimal.ZERO, BigDecimal.ZERO)\n        val collectiveHint = CloudShareCollectiveCache.score(ticker.symbol, selected.mode.name, regime.regime.name, "15m")\n        return if (CloudShareCollectiveCache.snapshot().enabled && collectiveHint.ready) selected.copy(reason = selected.reason + " | Collective vote hint: " + collectiveHint.reason) else selected\n'''
        text = replace_once(text, old, new, "strategy collective selection")
    path.write_text(text, encoding="utf-8")

def patch_controller(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if "ProductionIntelligenceEngine" not in text:
        text = replace_once(text, "import com.ksp.cryptobot.performance.PerformanceLabEngine\n", "import com.ksp.cryptobot.performance.PerformanceLabEngine\nimport com.ksp.cryptobot.governance.ProductionIntelligenceEngine\n", "controller production import")
        text = replace_once(text, "    private val selfLearningEngine = TrueSelfLearningEngine()\n", "    private val selfLearningEngine = TrueSelfLearningEngine()\n    private val productionIntelligence = ProductionIntelligenceEngine(AppDatabase.get(appContext).governanceDao())\n", "controller production field")
    if "Production intelligence:" not in text:
        old='''                val learningResult = selfLearningEngine.adjustDecision(dao, autonomousDecision, ticker, settings)\n                val decision = learningResult.decision\n                if (settings.trueSelfLearningEnabled) updateStatus("[$symbol] ${learningResult.explanation.take(220)}", "INFO")\n                val replay = autonomousPack.buildTradeReplay(decision, ticker, settings)\n'''
        new='''                val learningResult = selfLearningEngine.adjustDecision(dao, autonomousDecision, ticker, settings)\n                val learnedDecision = learningResult.decision\n                if (settings.trueSelfLearningEnabled) updateStatus("[$symbol] ${learningResult.explanation.take(220)}", "INFO")\n                val productionResult = productionIntelligence.evaluateDecision(\n                    learnedDecision, ticker, candlesByTimeframe[Timeframe.M15].orEmpty(), recentTrades, settings\n                )\n                val decision = productionResult.first\n                val production = productionResult.second\n                updateStatus("[$symbol] Production intelligence: blocked=${production.blocked}, adj=${production.scoreAdjustment}, size×${"%.2f".format(production.sizeMultiplier)}, safe=${production.safeMode.level}, anomaly=${production.anomaly.severity}, kill=${production.killSwitch.severity}. ${production.reason.take(240)}", if (production.blocked) "WARN" else "INFO")\n                val replay = autonomousPack.buildTradeReplay(decision, ticker, settings)\n'''
        text = replace_once(text, old, new, "controller production decision")
    if "recordWhyNotTrade(decision, settings, allowed.second)" not in text:
        old='''        if (!allowed.first) {\n            updateStatus("Trade blocked: ${allowed.second}", "WARN")\n            return ExecutionAttemptResult(false)\n        }\n'''
        new='''        if (!allowed.first) {\n            productionIntelligence.recordWhyNotTrade(decision, settings, allowed.second)\n            updateStatus("Trade blocked: ${allowed.second}", "WARN")\n            return ExecutionAttemptResult(false)\n        }\n'''
        text = replace_once(text, old, new, "controller why-not guard")
    if "recordWhyNotTrade(decision, settings, proNetCheck.reason)" not in text:
        old='''        if (!proNetCheck.allowed) {\n            return ExecutionAttemptResult(false)\n        }\n'''
        new='''        if (!proNetCheck.allowed) {\n            productionIntelligence.recordWhyNotTrade(decision, settings, proNetCheck.reason)\n            return ExecutionAttemptResult(false)\n        }\n'''
        text = replace_once(text, old, new, "controller why-not net")
    if "productionIntelligence.observeExecution(" not in text:
        old='''        )\n        updateStatus("Order placed: ${result.side} ${result.symbol} ${if (result.paper) "PAPER" else "LIVE"}. qty=${executedQtyForRecord.stripTrailingZeros().toPlainString()} avg=${averagePriceForRecord.stripTrailingZeros().toPlainString()} fee=${feeForRecord.stripTrailingZeros().toPlainString()} orderId=${result.exchangeOrderId}", if (result.paper) "INFO" else "LIVE")\n'''
        new='''        )\n        productionIntelligence.observeExecution(\n            symbol = result.symbol,\n            side = result.side,\n            mode = if (result.paper) "PAPER" else "LIVE",\n            orderType = request.orderType,\n            expectedPrice = price,\n            actualPrice = averagePriceForRecord,\n            quantity = executedQtyForRecord,\n            clientOrderId = request.clientOrderId,\n            exchangeOrderId = result.exchangeOrderId\n        )\n        updateStatus("Order placed: ${result.side} ${result.symbol} ${if (result.paper) "PAPER" else "LIVE"}. qty=${executedQtyForRecord.stripTrailingZeros().toPlainString()} avg=${averagePriceForRecord.stripTrailingZeros().toPlainString()} fee=${feeForRecord.stripTrailingZeros().toPlainString()} orderId=${result.exchangeOrderId}", if (result.paper) "INFO" else "LIVE")\n'''
        text = replace_once(text, old, new, "controller execution quality")
    path.write_text(text, encoding="utf-8")

def patch_version(gradle: Path) -> None:
    text = gradle.read_text(encoding="utf-8")
    text, c1 = re.subn(r'versionCode\s*=\s*(?:97|100|101|102)\b', 'versionCode = 102', text, count=1)
    text, c2 = re.subn(r'versionName\s*=\s*"(?:3\.2\.5|4\.0\.0-m1|4\.0\.0-m2|4\.0\.0-m3)"', 'versionName = "4.0.0-m3"', text, count=1)
    if c1 != 1 or c2 != 1: fail(f"Cannot patch Gradle version: code={c1}, name={c2}")
    gradle.write_text(text, encoding="utf-8")

def main() -> None:
    if len(sys.argv) != 2: fail("Usage: python apply_milestone3.py <path-to-Trading-Station-repo>")
    repo = Path(sys.argv[1]).resolve(); app = repo / "app"; src = app / "src/main/java/com/ksp/cryptobot"
    gradle=app/"build.gradle.kts"; service=src/"service/BotForegroundService.kt"; controller=src/"core/BotController.kt"; strategy=src/"strategy/MultiStrategyEngine.kt"
    required=(gradle,service,controller,strategy,src/"data/AppDatabase.kt")
    if not all(p.exists() for p in required): fail("Target does not look like the current Demon-blood/Trading-Station source tree.")
    backup=repo/".v4_m3_backup"; backup.mkdir(exist_ok=True)
    for p in required:
        t=backup/p.relative_to(repo); t.parent.mkdir(parents=True,exist_ok=True); shutil.copy2(p,t)
    copy_overlay(src)
    patch_service(service); patch_strategy(strategy); patch_controller(controller); patch_version(gradle)
    print("Applied cumulative Crypto TradeStation Android v4 Milestone 3.")
    print(f"Backup: {backup}")
    print("Database: explicit migrations 6->7->8->9; no destructive fallback.")
    print("Governance: anomaly firewall, safe mode, kill switch, risk budget, counterfactual, execution quality, watchdog/crash evidence.")
    print("Next build: ./gradlew clean :app:assembleDebug")

if __name__ == "__main__": main()
