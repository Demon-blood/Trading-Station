#!/usr/bin/env python3
"""Static/integrity validation for the research-handoff truth automation payload.
Runs without Android/Gradle so GitHub Actions can fail early on a corrupted migration pack.
"""
from __future__ import annotations
import hashlib, json, sys
from pathlib import Path

ROOT=Path(__file__).resolve().parent
ASSETS=ROOT/'app/src/main/assets/research_handoff'
SRC=ROOT/'app/src/main/java/com/ksp/cryptobot'
M4=ROOT/'_baseline_m5/_baseline_m4/apply_milestone4.py'
EXPECTED_ASSETS={
'BELGIUM_KRAKEN_CONSTRAINTS.md','EVIDENCE_MATRIX.md','HANDOFF_PROMPT.md','IMPLEMENTATION_SPEC.md','MANIFEST.json','README.md','RESEARCH_SOURCES.md','SOURCE_REGISTRY.json','STRATEGY_CATALOG.json','STRATEGY_TRUTH_STANDARD.md','TRADER_DUE_DILIGENCE.md','UNVERIFIED_AND_PROPRIETARY.md','VIDEO_EXTRACTION_NOTES.md','VIDEO_RESEARCH_INDEX.csv','WEEKLY_RESEARCH_RUNBOOK.md'}
ALIASES={
'tcg_equilibrium':'tcg_video_library','tcg_inside_bar':'tcg_video_library','tcg_correlations':'tcg_video_library','loukas_current':'loukas_channel','loukas_cycles_trader':'loukas_channel','rastani_opening_gap':'rastani_site','rastani_elliott':'rastani_channel'}

def fail(msg:str)->None:
    raise SystemExit('HANDOFF_TRUTH_VALIDATION_FAIL: '+msg)

def must(path:Path,*needles:str)->None:
    if not path.exists(): fail(f'missing {path.relative_to(ROOT)}')
    text=path.read_text(encoding='utf-8')
    for n in needles:
        if n not in text: fail(f'{path.relative_to(ROOT)} missing invariant: {n}')

def main()->None:
    if not ASSETS.is_dir(): fail('research_handoff asset directory missing')
    got={p.name for p in ASSETS.iterdir() if p.is_file()}
    missing=EXPECTED_ASSETS-got
    if missing: fail('missing handoff assets: '+','.join(sorted(missing)))
    catalog=json.loads((ASSETS/'STRATEGY_CATALOG.json').read_text(encoding='utf-8'))
    rows=catalog if isinstance(catalog,list) else catalog.get('strategies',[])
    if len(rows)!=31: fail(f'expected 31 strategy records, got {len(rows)}')
    ids=[str(r.get('id','')) for r in rows]
    if len(set(ids))!=31 or any(not x for x in ids): fail('strategy IDs missing/duplicated')
    exact_blocked={r['id'] for r in rows if str(r.get('fidelity','')).upper()=='X'}
    if exact_blocked!={'krown_vmp_exact','cowen_price_risk_exact'}: fail(f'proprietary exact block set changed: {sorted(exact_blocked)}')
    # Current source freeze intentionally has zero positive live-truth PASS records. This must not be
    # silently rewritten by an implementation patch; a future research update should deliberately update the catalog.
    live_pass=[r['id'] for r in rows if str(r.get('live_truth_gate','')).upper()=='PASS']
    if live_pass: fail(f'unexpected live-truth PASS without a research-freeze update: {live_pass}')
    registry=json.loads((ASSETS/'SOURCE_REGISTRY.json').read_text(encoding='utf-8')).get('sources',[])
    reg={str(r.get('id','')) for r in registry}
    unresolved=[]
    for r in rows:
        for ref in r.get('source_refs',[]):
            if ref not in reg and ALIASES.get(ref) not in reg: unresolved.append(f"{r['id']}->{ref}")
    if unresolved: fail('unresolved source refs: '+','.join(unresolved))
    must(SRC/'research/ResearchHandoffEngine.kt',
         'check(rows.size == 31)', 'BLOCK_EMPIRICAL_PROMOTION', 'EMPIRICAL_WARMUP',
         'candidate.entryPlan.resting', 'WEEKLY_RESEARCH_RUNBOOK.md')
    must(SRC/'research/ResearchCoordinator.kt','handoffCanStage','handoffProtective','handoffSelected=')
    must(SRC/'research/ResearchHandoffCostRiskEngine.kt','SKIP_EXCHANGE_MIN_EXCEEDS_RISK','postOnlyPreferred')
    must(SRC/'research/ResearchHandoffStructureEngine.kt','data gap exceeds 3 bars','invalid OHLCV geometry','UTC interval-aligned')
    must(SRC/'exchange/PaperExchangeClient.kt','paper_orders_v4','paper_cost_basis_v4','Paper post-only rejected','realizedPnlQuote = realizedPnl','Deferred PAPER fill:')
    must(ROOT/'apply_milestone6.py','TradeVolume','costmin','tick_size','realizedPnlQuote','oflags','protectiveStopPrice','close[ordertype]','roundKrakenPriceToTick','copy_overlay')
    must(SRC/'execution/ProtectiveStopManager.kt','protectOrFlatten','cancelProtectiveStops','EMERGENCY_FLATTEN_UNPROTECTED','UNPROTECTED_POSITION','activeStops')
    must(M4,'Lifecycle SELL accepted without confirmed fill','realizedPnlEur = realized.toPlainString()','placed.paper','strategy=${persistedHandoffPlan?.strategyId','Paper lifecycle pre-scan truth','ProtectiveStopManager','protectiveStopPrice','sourceManagedLiveExit','Deferred handoff fill protection')
    digest=hashlib.sha256((ASSETS/'STRATEGY_CATALOG.json').read_bytes()).hexdigest()
    print('HANDOFF_TRUTH_VALIDATION_PASS')
    print(f'assets={len(EXPECTED_ASSETS)} strategies={len(rows)} registry={len(registry)} source_unknown_blocked={len(exact_blocked)} current_live_truth_pass={len(live_pass)}')
    print('catalog_sha256='+digest)

if __name__=='__main__': main()
