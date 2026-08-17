# Milestone 3 — Governance + Production Safety

## Purpose

Port the deterministic safety/governance portion of desktop v1.0.50 into the Android decision and order lifecycle without introducing an opaque model or weakening any existing Android guard.

## Decision flow

1. Android builds the normal technical/news/memory/collective/self-learning decision.
2. M3 evaluates market-data anomalies using the current ticker and M15 candle history.
3. It evaluates safe mode from realized daily P/L and recent anomaly/runtime evidence.
4. It applies a scoped kill switch for hard daily loss, operational-error bursts, and repeated meaningful recent losses.
5. It calculates daily risk-budget state and a bounded size multiplier.
6. It evaluates historical execution slippage for the symbol/side/mode.
7. It evaluates a bounded counterfactual delayed-entry scenario.
8. Score adjustments are capped to `-12..+6`; blocking results convert new BUY entries to WAIT / `allowedToTrade=false`.
9. The normal Android `ExecutionGuard` still runs afterwards and remains authoritative.
10. Actual fills are observed as execution-quality evidence using requested reference price vs reported average fill price.

## PAPER guard correction

The previous Android guard returned `true` immediately for PAPER mode. That meant it skipped `allowedToTrade`, action, and confidence checks. M3 validates those first in every mode, then permits PAPER execution only if the decision remains executable.

## Persistence

Room v9 adds:

- `governance_events`
- `execution_quality_events`
- `production_intelligence_state`

Migration `8 -> 9` is explicit and non-destructive.

## CloudShare

M3 uploads sanitized raw governance/execution evidence and emits desktop-compatible daily aggregates for anomaly, guard, execution quality, risk budget, safe mode, watchdog, crash recovery, and counterfactual learning.

## Intentional M4 boundary

M3 calculates `ProductionIntelligenceRuntime.sizeMultiplier`, but final order sizing is not changed yet. M4 consumes that multiplier alongside liquidity-aware sizing, order-type optimization, portfolio allocation, exit optimization, and live reconciliation. This avoids changing both decision governance and monetary execution sizing in the same validation boundary.
