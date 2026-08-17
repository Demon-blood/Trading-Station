# Crypto TradeStation Android v4 — Milestone 4 (Advanced Execution + Portfolio/Risk)

This is the **cumulative** Android v4 source overlay for `Demon-blood/Trading-Station`. It contains M1 + M2 + M3 + M4 and can be applied directly to the untouched Android `3.2.5` source or over a previous milestone.

## Apply on Windows

```powershell
python .\apply_milestone4.py C:\path\to\Trading-Station
cd C:\path\to\Trading-Station
.\gradlew clean :app:assembleDebug
```

The public installer creates `.v4_m4_backup`. Its internal M3 baseline helper is named `_apply_m3_baseline_for_m4.py`; use `apply_milestone4.py`, not the helper directly.

## Target build

- versionName: `4.0.0-m4`
- versionCode: `103`
- Room database: version `10`
- explicit migrations: `6 -> 7 -> 8 -> 9 -> 10`
- no `fallbackToDestructiveMigration()`

## M4 adds

- consumes the M3 production/safe-mode size multiplier in the **actual BUY notional**;
- capital-protection ladder based on current daily-loss-budget usage;
- portfolio/capital-allocation layer with duplicate-base concentration protection and performance/open-position scaling;
- absolute ceiling rule: M4 intelligence can never increase above the controller's already-calculated balance/reserve target;
- liquidity-aware sizing using top-10 order-book depth plus the existing configured depth multiple;
- spread-sensitive size reduction;
- fee-efficiency rule that fails closed instead of undoing an earlier risk/liquidity reduction;
- order-type optimizer that can downgrade MARKET to LIMIT when spread/depth are not suitable;
- no automatic upgrade from LIMIT to live MARKET: `enableMarketOrders` and existing Android market guards stay authoritative;
- live position reconciliation against exchange balances with a 2% quantity-difference threshold;
- explicit reconciliation evidence and CloudShare aggregate publishing;
- lifecycle exit optimizer with hard-risk full exits, trailing full exits, partial TP, spike-exhaustion partial exits and soft fee-churn deferral;
- existing learned-hold and spike-timing checks remain ahead of the new exit optimizer;
- realistic paper execution using live Kraken depth when available, maker/taker fee assumptions, slippage and partial fills;
- existing Android live/paper shadow comparison remains active;
- persistent `advanced_execution_events` ledger;
- CloudShare desktop-compatible `shared_order_type_daily`, `shared_liquidity_sizing_daily`, `shared_exit_daily`, `shared_reconciliation_daily`, and `shared_paper_execution_daily` aggregates.

## Safety invariants

1. Exchange balances/reserves and max-position logic still establish the initial notional ceiling.
2. M3 production intelligence can only keep or reduce that ceiling.
3. M4 portfolio/liquidity layers can only keep or reduce it further.
4. The fee-efficient floor never raises a notional after a safety reduction; it blocks instead.
5. Existing spread, order-book, cooldown, duplicate-position, portfolio exposure, LIVE_AUTO acknowledgement, backtest/forward-test and stop-loss gates remain in force.
6. Hard-risk exits are never delayed by the M4 soft fee-efficiency rule.

See `docs/MILESTONE_4.md` and `VALIDATION_REPORT_M4.json`.
