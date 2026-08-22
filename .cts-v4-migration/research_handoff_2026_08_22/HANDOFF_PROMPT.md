# Handoff Prompt for the Next Conversation

Use the attached `crypto_trading_research_handoff_2026-08-17.zip` as the authoritative research handoff.

## Goal
Implement the researched trading framework into my Crypto TradeStation app **without changing the meaning of the sourced strategies** and without inventing proprietary/unknown rules.

## Mandatory rules
1. Read `README.md`, `IMPLEMENTATION_SPEC.md`, `STRATEGY_CATALOG.json`, `BELGIUM_KRAKEN_CONSTRAINTS.md`, `UNVERIFIED_AND_PROPRIETARY.md`, and `VIDEO_RESEARCH_INDEX.csv` before coding.
2. Preserve every strategy's `fidelity`, `provenance`, version and `must_not_claim`.
3. Do not merge creator strategies into one strategy unless we explicitly create and name a separate composite experiment.
4. Do not implement any `fidelity=X` / `PROPRIETARY_UNKNOWN` rule as if exact.
5. If a source rule is discretionary, expose the formalization parameters and label it `FORMALIZED`, not `SOURCE-EXACT`.
6. Belgium profile defaults to long-only crypto spot. Bearish/short source signals become `EXIT`, `REDUCE`, or `AVOID` unless I explicitly request a legally supported product after a fresh compliance check.
7. Use Kraken live market metadata for tradability, precision, min order, status and restrictions; do not rely permanently on the 2026-08-17 snapshot.
8. Use the actual Kraken fee tier when available. Include maker/taker fee, spread and slippage in all backtests and risk sizing.
9. If minimum order size violates risk budget, skip the trade.
10. Implement risk, execution, audit and backtest foundations before enabling live strategies.
11. Closed-candle/no-lookahead rules are mandatory.
12. Every live/paper decision must log:
   - strategy ID/version
   - source/provenance
   - signal features
   - entry/invalidation/targets
   - expected gross and net edge
   - fee/slippage estimate
   - risk budget and calculated quantity
   - compliance/cost/risk gate results
   - actual fills and fees
   - outcome in EUR and R
13. Build unit tests and historical/walk-forward tests before live promotion.
14. No statement such as "proven profitable" merely because the original trader is famous or a backtest looks good.

## Initial implementation order
1. Compliance + Kraken registry / cost / risk / execution infrastructure.
2. Brandt classical ATR breakout formalization + 3DTSR management.
3. CryptoCred S/R first-retest + top-down + FTA modules.
4. Chart Guys equilibrium / inside-bar; then bullish BackBurner formalization.
5. Independent regime layer inspired by Cowen/Loukas/Krown architecture, while keeping proprietary formulas excluded.
6. Pizzino cycle/swing context.
7. Rastani/Elliott/session-gap only in research mode.

## Before modifying code
Inspect the current app/repository first, map existing modules to the architecture above, and propose the smallest safe release plan. Preserve existing working trading/data functionality.

If the package and the existing code disagree, do not silently pick one: report the conflict and explain whether it is a research-definition conflict, exchange/API change, or existing-app implementation bug.

## Mandatory strategy-truth extension

15. Read `STRATEGY_TRUTH_STANDARD.md` before implementing any strategy.
16. Implement the **real-life strategy and its real usage conditions**, not merely a similarly named pattern.
17. For each strategy establish the complete chain:
   `purpose → regime → timeframe → prerequisites → setup → trigger → entry → invalidation → stop → sizing → management → exit → no-trade conditions`.
18. If a material rule is unknown, leave it unknown and block `SOURCE_FAITHFUL`; do not fill it with a plausible trading convention.
19. Preserve historical/current versions separately when the trader's method evolved.
20. Every Belgium/Kraken change to the original method must be recorded as an adaptation and must not be attributed to the creator.
21. Before enabling a strategy live, show me its source-faithfulness report and unresolved unknowns.

