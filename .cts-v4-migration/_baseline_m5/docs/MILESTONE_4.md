# M4 — Advanced Execution + Portfolio/Risk

## Objective

Turn the M3 governance output into bounded execution behavior without allowing an intelligence layer to bypass the Android controller's existing capital, exchange or safety constraints.

## Entry pipeline

The BUY path now applies these stages in order:

1. Existing controller balance/reserve/per-order calculation.
2. M3 `ProductionIntelligenceRuntime.sizeMultiplier`.
3. Capital-protection ladder.
4. Portfolio allocation / concentration / performance scaling.
5. Liquidity-aware top-10 depth sizing.
6. Fee-efficient minimum gate.
7. Order-type optimization.
8. Existing order-book depth/slippage guard.
9. Existing Kraken order submission and post-fill evidence.

The original calculated target is an absolute upper bound. Later stages cannot increase it.

## Capital protection ladder

M4 derives ladder thresholds from the existing `maxDailyLossEur` setting rather than introducing hidden new risk settings:

- level 0: <25% budget used -> normal size
- level 1: >=25% -> 0.75x
- level 2: >=50% -> 0.50x
- level 3: >=75% in live mode -> block new live entries
- level 4: >=100% -> block new entries / manage exits only

## Liquidity-aware sizing

The top ten ask levels are converted to quote depth. The existing `minOrderBookDepthMultiple` setting is reused as the maximum depth-usage relation. Elevated spread applies an additional 0.50x reduction. The existing final order-book guard still runs afterward.

## Order type

- elevated spread -> LIMIT, using a passive midpoint where possible;
- an already-requested MARKET order is retained only when top-five depth is strong and spread is very tight;
- otherwise MARKET is downgraded to LIMIT;
- M4 never turns a normal live LIMIT request into MARKET on its own.

## Reconciliation

For live modes, local open position quantities are compared with exchange portfolio balances. A difference over 2% repairs the local quantity. Zero exchange balance closes the stale local row as `RECONCILED_ZERO`. Open order count is recorded as evidence.

## Exit optimization

The existing sequence remains:

- stop / trailing / TP / bearish/spike trigger
- spike-timing hold check
- learned-hold check
- duplicate SELL-order check
- M4 exit optimizer

M4 then selects full/partial fraction and MARKET/LIMIT policy. Hard-risk exits remain full exits. A soft TP below +0.25% can be deferred to avoid fee churn; hard-risk exits bypass this.

## Realistic paper execution

Paper mode now uses live Kraken order-book depth when available. It uses:

- LIMIT maker assumption: 0.16%
- MARKET taker assumption: 0.26%
- MARKET base slippage: 0.03%
- depth-dependent slippage term
- spread/volatility slippage term
- partial-fill simulation for non-crossing limits and large depth usage

No paper path can send a real order.

## CloudShare

New desktop-compatible aggregate families:

- `shared_order_type_daily`
- `shared_liquidity_sizing_daily`
- `shared_exit_daily`
- `shared_reconciliation_daily`
- `shared_paper_execution_daily`
