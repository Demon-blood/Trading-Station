#!/usr/bin/env python3
"""Apply cumulative Crypto TradeStation Android v4 Milestone 5 to v3.2.5 or any prior v4 milestone."""
from __future__ import annotations
import re, shutil, subprocess, sys
from pathlib import Path
HERE=Path(__file__).resolve().parent

def fail(msg:str)->None: raise SystemExit(msg)
def replace_once(text:str,old:str,new:str,label:str)->str:
    count=text.count(old)
    if count!=1: fail(f"Cannot patch {label}: expected exactly one match, found {count}.")
    return text.replace(old,new,1)

def run_m4(repo:Path)->None:
    gradle=(repo/'app/build.gradle.kts').read_text(encoding='utf-8')
    controller=(repo/'app/src/main/java/com/ksp/cryptobot/core/BotController.kt').read_text(encoding='utf-8')
    database=(repo/'app/src/main/java/com/ksp/cryptobot/data/AppDatabase.kt').read_text(encoding='utf-8') if (repo/'app/src/main/java/com/ksp/cryptobot/data/AppDatabase.kt').exists() else ''
    if ('versionName = "4.0.0-m4"' in gradle or 'versionName = "4.0.0-m5"' in gradle) and 'AdvancedExecutionCoordinator' in controller and ('version = 10' in database or 'version = 11' in database):
        print('M4 baseline already present; skipping M4 reapply.')
        return
    script=HERE/'_baseline_m4/apply_milestone4.py'
    result=subprocess.run([sys.executable,str(script),str(repo)],text=True,capture_output=True)
    if result.returncode!=0: fail("Cumulative M4 baseline failed before M5 patching:\n"+result.stdout+result.stderr)
    print(result.stdout.strip())

def copy_overlay(repo:Path)->None:
    source=HERE/'app/src/main/java/com/ksp/cryptobot'; target=repo/'app/src/main/java/com/ksp/cryptobot'
    for folder in ('cloudshare','data','ui','intelligence','governance','execution','research'):
        s=source/folder
        if not s.exists(): continue
        d=target/folder;d.mkdir(parents=True,exist_ok=True)
        for f in s.glob('*.kt'): shutil.copy2(f,d/f.name)
    st=HERE/'app/src/test/java/com/ksp/cryptobot'
    if st.exists():
        dt=repo/'app/src/test/java/com/ksp/cryptobot';dt.mkdir(parents=True,exist_ok=True)
        for f in st.rglob('*.kt'):
            out=dt/f.relative_to(st);out.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(f,out)

def backup(paths:list[Path],repo:Path)->Path:
    root=repo/'.v4_m5_backup';root.mkdir(exist_ok=True)
    for p in paths:
        if p.exists():
            t=root/p.relative_to(repo);t.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,t)
    return root

def patch_controller(path:Path)->None:
    text=path.read_text(encoding='utf-8')
    if 'ResearchCoordinator' not in text:
        text=replace_once(text,'import com.ksp.cryptobot.governance.ProductionIntelligenceEngine\n','import com.ksp.cryptobot.governance.ProductionIntelligenceEngine\nimport com.ksp.cryptobot.research.ResearchCoordinator\n','controller research import')
        text=replace_once(text,'    private val productionIntelligence = ProductionIntelligenceEngine(AppDatabase.get(appContext).governanceDao())\n','    private val productionIntelligence = ProductionIntelligenceEngine(AppDatabase.get(appContext).governanceDao())\n    private val researchIntelligence = ResearchCoordinator(appContext, AppDatabase.get(appContext).researchDao())\n','controller research field')
    if 'Research broad context:' not in text:
        marker='''        val decisions = symbols.mapNotNull { symbol ->\n'''
        block='''        val researchBroadContext = runCatching { researchIntelligence.loadBroadContext(exchange) }\n            .onFailure { updateStatus("Research broad context unavailable: ${it.message}", "WARN") }\n            .getOrDefault(com.ksp.cryptobot.research.BroadMarketContext())\n        updateStatus("Research broad context: BTC=${"%.2f".format(researchBroadContext.btcMomentumPct)}%, ETH=${"%.2f".format(researchBroadContext.ethMomentumPct)}%, broad=${"%.2f".format(researchBroadContext.broadMomentumPct)}%", "INFO")\n        val decisions = symbols.mapNotNull { symbol ->\n'''
        text=replace_once(text,marker,block,'controller broad research context')
    if 'Research intelligence:' not in text:
        old='''                val productionResult = productionIntelligence.evaluateDecision(\n                    learnedDecision, ticker, candlesByTimeframe[Timeframe.M15].orEmpty(), recentTrades, settings\n                )\n'''
        new='''                val researchResult = researchIntelligence.evaluateDecision(\n                    settings = settings, decision = learnedDecision, ticker = ticker, candlesByTimeframe = candlesByTimeframe,\n                    recentTrades = recentTrades, news = news, exchange = exchange, broad = researchBroadContext\n                )\n                val researchedDecision = researchResult.first\n                val research = researchResult.second\n                updateStatus("[$symbol] Research intelligence: strategy=${research.selectedStrategy}, adj=${research.scoreAdjustment}, regime=${research.regime.regime}, WF=${"%.1f".format(research.walkForward.score)}, MC=${"%.1f".format(research.monteCarlo.score)}, seq=${research.sequence.adjustment}, RL=${research.rlSandbox.adjustment}, promoted=${research.promotedFromResearch}. ${research.explanation.take(260)}", if (research.allowed) "INFO" else "WARN")\n                val productionResult = productionIntelligence.evaluateDecision(\n                    researchedDecision, ticker, candlesByTimeframe[Timeframe.M15].orEmpty(), recentTrades, settings\n                )\n'''
        text=replace_once(text,old,new,'controller research decision hook')
    path.write_text(text,encoding='utf-8')

def patch_version(gradle:Path)->None:
    text=gradle.read_text(encoding='utf-8')
    text,c1=re.subn(r'versionCode\s*=\s*(?:97|100|101|102|103|104)\b','versionCode = 104',text,count=1)
    text,c2=re.subn(r'versionName\s*=\s*"(?:3\.2\.5|4\.0\.0-m1|4\.0\.0-m2|4\.0\.0-m3|4\.0\.0-m4|4\.0\.0-m5)"','versionName = "4.0.0-m5"',text,count=1)
    if c1!=1 or c2!=1: fail(f"Cannot patch Gradle version: code={c1}, name={c2}")
    gradle.write_text(text,encoding='utf-8')

def main()->None:
    if len(sys.argv)!=2: fail('Usage: python apply_milestone5.py <path-to-Trading-Station-repo>')
    repo=Path(sys.argv[1]).resolve();src=repo/'app/src/main/java/com/ksp/cryptobot';controller=src/'core/BotController.kt';gradle=repo/'app/build.gradle.kts'
    if not controller.exists() or not gradle.exists(): fail('Target does not look like the current Demon-blood/Trading-Station source tree.')
    run_m4(repo)
    b=backup([controller,gradle,src/'data/AppDatabase.kt',src/'cloudshare/CloudShareSyncEngine.kt'],repo)
    copy_overlay(repo);patch_controller(controller);patch_version(gradle)
    print('Applied cumulative Crypto TradeStation Android v4 Milestone 5.')
    print(f'M5 backup: {b}')
    print('Database: explicit migrations 6->7->8->9->10->11; no destructive fallback.')
    print('Research: 23 expanded strategy votes, advanced regimes, walk-forward, Monte Carlo, meta/cross-symbol, mutation/hypothesis, sequence model, RL sandbox, order-book replay, futures and labeled-wallet context.')
    print('Safety: research runs before M3 production governance; M4 capital ceilings remain authoritative. Research-originated LIVE entries remain disabled by default.')
    print('Next build: ./gradlew clean :app:assembleDebug')
if __name__=='__main__': main()
