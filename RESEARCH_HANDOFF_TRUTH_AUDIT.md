# Research Handoff Truth Audit

**Research freeze:** 2026-08-17/18  
**Target:** Crypto TradeStation Android v4.0.0 / versionCode 105 / Room 11  
**Execution venue:** Kraken spot for intended LIVE_AUTO profile; other exchanges/futures are reference/research only.

## 1. Research pack ingestion

All supplied handoff data is embedded in Android assets and checked by `validate_handoff_truth.py` plus runtime catalog verification.

- handoff asset files: **15/15**
- strategy records: **31/31**
- source-registry entries: **46**
- unresolved source references: **0**
- proprietary exact strategies intentionally blocked: **2**
- current positive `live_truth_gate=PASS` rows: **0**, matching the supplied research freeze

The two intentionally non-implemented proprietary strategies are:

- `krown_vmp_exact`
- `cowen_price_risk_exact`

No generic indicator blend is substituted under either proprietary name.

## 2. Strategy layers

The Android application keeps three distinct layers instead of one mega-strategy:

1. **Desktop-parity layer** — behavioral port of the desktop strategy families and research semantics.
2. **Professional/practitioner layer** — fuller DMI/ADX, iterative Supertrend, PSAR, anchored VWAP, volume/OBV, z-score, market structure, MTF/trend/volatility/execution variants.
3. **Research-handoff laboratory** — one versioned detector per supplied strategy record, preserving creator/source/fidelity/usage-context metadata.

The handoff runtime executes all 31 detector records on every eligible scan. A local runtime harness produced:

```text
evaluated=31
statuses={WARMUP=5, NO_SETUP=11, CONTEXT=12, FORMALIZED_RESEARCH=1, BLOCKED_SOURCE_UNKNOWN=2}
ALL_31_HANDOFF_DETECTORS_RUNTIME=PASS
```

Those counts are a deterministic harness state, not claimed market performance.

## 3. Market-data truth

The handoff structure engine rejects strategy computation when it detects:

- invalid or duplicate timestamps;
- unsorted bars;
- invalid OHLCV geometry;
- negative volume;
- unfinished higher-timeframe bars;
- UTC interval misalignment;
- gaps larger than the configured three-bar continuity policy.

Missing optional macro/external context is neutral or risk-reducing, never silently converted into bullish evidence.

## 4. Cost and liquidity truth

The cost gate models entry fee, exit fee, spread/slippage and safety margin without calling a short-horizon gross move profitable before costs.

LIVE fee source preference:

1. account/pair-specific Kraken fee schedule from `TradeVolume`;
2. conservative configured/fallback fee assumptions if private fee data is unavailable.

Order semantics:

- only explicit post-only LIMIT is pre-costed as maker;
- ordinary LIMIT, marketable LIMIT, STOP and TAKE_PROFIT use conservative taker economics where appropriate;
- post-only crossing is rejected rather than silently converted to taker;
- Kraken amount minimum, minimum order cost and tick size are validated.

Local cost/risk harness:

```text
HANDOFF_COST_RISK_TRUTH_TEST=PASS
SKIP_EXCHANGE_MIN_EXCEEDS_RISK verified
STOP entry uses taker fee assumption
post-only LIMIT uses maker fee assumption
```

## 5. Risk sizing and portfolio controls

The supplied loss-per-unit equation is implemented. Final quantity is constrained by:

- configured per-trade risk budget;
- technical/source stop;
- entry and exit fees;
- entry and exit slippage;
- available quote cash;
- amount precision;
- Kraken `ordermin`;
- Kraken `costmin`;
- M3 risk/safe-mode/kill-switch state;
- M4 capital ceiling;
- liquidity/depth constraints;
- correlated/campaign risk cap.

An exchange minimum is never allowed to raise modeled risk above the risk budget.

## 6. Empirical promotion

Creator/source fidelity and empirical evidence are separate gates.

Positive LIVE promotion requires strategy-ID-specific realized outcomes and then:

- minimum outcome sample count;
- rolling walk-forward readiness/score;
- Monte-Carlo readiness/score;
- positive-probability threshold;
- profit-factor threshold;
- positive net realized P&L;
- additional fee/slippage stress remaining positive.

PAPER automatically generates evidence. Current handoff source truth still blocks positive LIVE entries until a deliberate source-reverification update changes the catalog. This is intentional compliance with the supplied truth standard.

## 7. PAPER execution truth

`PaperExchangeClient` now has persistent pending-order and cost-basis ledgers.

- MARKET: immediate taker-style simulated fill using real public Kraken market data when available.
- post-only LIMIT: rejected if crossing; otherwise remains pending and maker-style only when actually resting.
- ordinary LIMIT: pending until marketable/crossed.
- STOP_LOSS / TAKE_PROFIT: pending until their real trigger relation is observed.
- pending orders do not mutate the paper wallet.
- deferred fills are journaled.
- entry cost basis includes fees.
- SELL fills calculate realized P&L from allocated cost basis.
- source stops/targets are managed automatically in PAPER as well as LIVE.

Runtime invariant test:

```text
PAPER_PENDING_WALLET_TRUTH_PASS open=1 marketQty=0.00010000
```

## 8. LIVE fill and protection truth

An accepted Kraken order with zero executed quantity is treated as **pending**, not as a completed trade. Trade history/P&L is written only from confirmed fill evidence.

For a confirmed sourced LIVE BUY:

1. source plan is persisted;
2. the BUY request carries `protectiveStopPrice`;
3. Kraken `AddOrder` receives a conditional `stop-loss` close when supported;
4. `ProtectiveStopManager` verifies live stop coverage;
5. if missing, a standalone STOP_LOSS is attempted;
6. if protection still cannot be established, an emergency MARKET flatten is attempted;
7. failure state is persisted as `UNPROTECTED_POSITION` with CRITICAL governance evidence.

For source-managed target/protective exits, an existing exchange stop is cancelled and verified before a competing SELL. After a confirmed partial exit the remaining quantity is re-protected. If exit submission is unfilled or fails after cancellation, the original remaining position is re-protected.

## 9. Research/proprietary boundary

The implementation deliberately does not claim:

- proprietary Krown VMP formula/settings;
- proprietary Cowen ITC Price Risk formula/weights;
- paid/private Chart Guys formulas as public exact rules;
- unique Elliott-Wave labels from price alone;
- session opening-gap behavior as an exact 24/7 crypto rule;
- historical futures/leverage returns as expected spot returns.

Where a public-core idea is formalized into a machine rule, its implementation class and explanation expose that formalization.

## 10. Validation boundary

Validated locally without Android SDK:

- desktop v1.0.50 test suite: **34 passed**;
- CloudShare Worker: **8 passed**;
- payload truth validator: PASS;
- 31-detector runtime: PASS;
- cost/risk truth runtime: PASS;
- PAPER pending/wallet runtime: PASS;
- handoff catalog/core/engine/execution Kotlin harnesses: PASS;
- `ProtectiveStopManager` Kotlin compile: PASS;
- controller protection patch fixture Kotlin compile: PASS;
- lifecycle stop-aware patch fixture Kotlin compile: PASS;
- Kraken patch fixture + idempotent reapply: PASS;
- M4/M6 Python installer syntax: PASS.

**Not validated locally:** full Android SDK/KSP/Compose compilation. GitHub Actions remains the authoritative APK build gate.
