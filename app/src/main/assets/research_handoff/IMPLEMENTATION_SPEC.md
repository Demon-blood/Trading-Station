# Implementation Specification

## 0. Product principle

Do **not** build "one mega-strategy" that averages all creators. Build an evidence-preserving strategy laboratory.

Each detector returns a `TradeCandidate`, not an order.

```python
@dataclass(frozen=True)
class TradeCandidate:
    strategy_id: str
    strategy_version: str
    symbol: str
    side_intent: Literal["LONG_ENTRY", "EXIT", "REDUCE", "AVOID"]
    signal_time_utc: datetime
    thesis_timeframe: str
    execution_timeframe: str
    entry_plan: EntryPlan
    invalidation: InvalidationPlan
    targets: list[TargetPlan]
    features: dict[str, float | str | bool]
    provenance: list[str]
    fidelity: Literal["A","B","C","X"]
    explanation: str
```

A candidate passes through separate compliance, cost, risk and execution gates.

---

# 1. Pipeline

## Stage A — Compliance / market registry

For every symbol:
- query/load Kraken instrument status;
- ensure spot market and asset are permitted for the account/region;
- precision/tick/lot/min-volume/min-cost validated;
- reject restricted/unavailable asset;
- default to EUR quote where liquid/available;
- no derivative/futures order path in Belgium spot profile.

**Never hardcode the current restricted-asset list as eternal truth.** Keep a date-stamped fallback only.

## Stage B — Market data integrity

Required:
- UTC timestamps
- no duplicate candles
- known gap policy
- closed-candle flag
- exchange/timeframe alignment
- corporate/token events where relevant
- order-book snapshot if strategy needs spread/liquidity

No strategy may compute on an unfinished higher-timeframe candle unless its source version explicitly uses intrabar data.

## Stage C — Regime engine

Independent features:
- BTC trend state
- BTC dominance state
- long-cycle state
- volatility percentile
- market breadth (if available)
- macro features (optional; never let missing macro silently become bullish)
- risk-on/risk-off state

Cowen/Loukas-derived concepts belong here, with explicit labels:
- `INDEPENDENT_RISK_BAND` — **not Cowen's proprietary Price Risk**
- `LOUKAS_CYCLE_FORMALIZATION_vX` — not an assertion that a cycle must repeat.

## Stage D — Structure engine

Reusable outputs:
- pivots / HH-HL-LH-LL
- ranges
- S/R levels/zones
- test counts
- break / reclaim / rejection
- equilibrium/inside bars
- pattern boundaries
- ATR / ADX / EMA/SMA
- volume/relative volume
- previous day/week high/low/open

All swing/pattern algorithms are versioned because "obvious" to a human is not a machine definition.

## Stage E — Strategy detectors

One class per strategy version. Examples:
- `BrandtClassicalAtrBreakout2026`
- `BrandtThreeDayTrailingStop`
- `CryptoCredSRFirstRetestV1`
- `CryptoCredPDLReclaimV1`
- `TCGBackBurnerBullishFormalizationV1`
- `TCGEquilibriumLongV1`
- `IndependentDynamicRiskDCAV1`

Never reuse a creator's trademarked/commercial indicator name for an independent reconstruction.

## Stage F — Cost / liquidity gate

For a long spot candidate estimate:

```text
entry_fee_quote     = entry_notional * entry_fee_rate
exit_fee_quote      = expected_exit_notional * exit_fee_rate
entry_slippage      = expected entry slippage in quote
exit_slippage       = expected exit slippage in quote
spread_cost         = any spread not already captured by fill model
expected_net_profit = expected_gross_profit
                      - entry_fee_quote
                      - exit_fee_quote
                      - entry_slippage
                      - exit_slippage
                      - spread_cost
```

Do not double-count spread and slippage if the fill model already includes both.

Reject if the expected gross move is too small to clear modeled costs plus the strategy's configured safety margin.

**Current research snapshot:** Kraken's lowest spot-volume tier was 0.40% maker / 0.80% taker. A maker→maker round trip is ~0.80% in trading fees alone; taker→taker ~1.60%, before spread/slippage. Fetch current account fee tier at runtime whenever possible.

This is why 5-minute YouTube scalps must not be promoted just because they look profitable before fees.

## Stage G — Risk sizing

### Correct spot-long base quantity

Let:
- `E` = intended entry price (EUR/base)
- `S` = stop fill estimate (EUR/base)
- `fe` = entry fee fraction
- `fx` = exit fee fraction
- `se` = expected entry slippage per base unit in EUR
- `sx` = expected stop-exit slippage per base unit in EUR
- `R` = maximum planned loss in EUR

Approximate loss per base unit if stopped:

```text
loss_per_unit =
    (E - S)
    + E * fe
    + S * fx
    + se
    + sx
```

Then:

```text
qty_risk = R / loss_per_unit
```

Cash cap:

```text
qty_cash = available_EUR / (E * (1 + fe) + se)
qty = min(qty_risk, qty_cash)
```

Round **down** to Kraken amount precision.

Then re-evaluate:
- amount minimum
- cost minimum
- resulting risk after rounding
- remaining cash reserve.

If minimum size would make actual modeled loss > risk budget:
`SKIP_MIN_ORDER_EXCEEDS_RISK`.

### Risk budget

The app should expose account-level policy separately from trader methods.

A conservative live-pilot default such as 0.25–0.50% risk/trade can be offered as **our safety policy**, not "the proven optimal percentage." Brandt's public historical nominal max is around 1% per trade in cited materials.

### Correlation/campaign risk

Use Brandt-inspired aggregate cap concept:
- group correlated symbols (BTC beta, ETH ecosystem, high-beta alts etc.);
- track worst-case stop loss per cluster;
- refuse a new trade if cluster exposure exceeds configured cap.

Do not hardcode Brandt's historical 200 bps correlated cap as universally optimal; make it a cited preset.

---

# 2. Execution semantics

## Maker-first vs taker

For a small account with a high fee tier:
- prefer resting/post-only limit entries where the strategy allows waiting;
- use market/taker only when the strategy's edge depends on immediate execution and expected net edge still clears costs;
- a post-only order that would cross must be cancelled/repriced according to explicit policy, never silently converted to taker.

## Breakout stop entries

Brandt-style breakout requires a resting trigger concept. If Kraken API semantics differ from historical futures GTC stop orders:
- implement exchange-supported stop/conditional order if available for spot and account;
- otherwise an app-side trigger must record latency/slippage risk and be separately backtested.

## Protective exits

Upon confirmed entry:
1. persist fill;
2. immediately ensure protective stop/conditional close exists if supported;
3. verify order acknowledgement;
4. if stop placement fails, enter `UNPROTECTED_POSITION` emergency state and apply configured safe action.

Never leave risk management dependent on the UI thread.

## Partial exits

Track base quantity precisely. Every partial fill recalculates:
- remaining cost basis
- realized P&L
- remaining stop quantity
- residual risk
- fees.

Do not call a residual position literally "risk-free"; use `REALIZED_PROFIT_COVERS_INITIAL_RISK` if true.

---

# 3. Strategy truth / provenance model

Every strategy config must include:

```json
{
  "strategy_id": "...",
  "creator_reference": "...",
  "source_version_date": "...",
  "fidelity": "A|B|C|X",
  "provenance": ["DIRECT_PUBLIC_VIDEO"],
  "creator_exact_rule": false,
  "formalization_notes": "...",
  "proprietary_fields": [],
  "parameters": {},
  "live_enabled": false
}
```

UI badges:
- `SOURCE-FAITHFUL`
- `FORMALIZED`
- `CONCEPT-INSPIRED`
- `PROPRIETARY / NOT IMPLEMENTED`

No `PROVEN` badge unless there is a separately defined empirical threshold and the wording is `passed app validation`, never `proven profitable forever`.

---

# 4. Backtest engine — mandatory controls

## Avoid look-ahead

For candle-close rules:
- features use data through close `t`;
- order can execute at `t+1` open or later, unless a resting order was already placed using information available before `t`.

For stop/limit intrabar simulation:
- if both stop and target lie inside the same OHLC bar and tick order is unknown, choose a conservative policy or require higher-resolution data.
- never choose the favorable sequence.

## Costs

Use:
- historical/current fee tier scenario
- maker/taker assumption per order type
- spread
- slippage
- missed limit fills
- partial fills if relevant.

Run at least:
- optimistic execution
- expected execution
- stressed execution.

## Walk-forward / OOS

Minimum process:
1. development window
2. untouched validation
3. rolling walk-forward windows
4. parameter sensitivity around selected values
5. regime stratification
6. final untouched holdout if dataset permits.

Do not pick the single best parameter combination and report it as truth.

## Metrics

Store at minimum:
- gross return
- net return
- CAGR where duration supports it
- max drawdown
- Calmar/MAR
- Sharpe
- Sortino
- profit factor
- expectancy
- win rate
- average win/loss
- median win/loss
- R-multiple distribution
- maximum consecutive losses
- time in market
- turnover
- number of trades
- fees as % gross profit
- slippage as % gross profit
- MAE / MFE
- average hold
- results by symbol
- results by year/quarter
- results by volatility regime
- results by bull/bear/range regime
- maker/taker split
- rejected candidates by reason.

### Expectancy

```text
E_R = P(win) * AvgWin_R - P(loss) * AvgLoss_R
```

A low win rate can be profitable if winners are sufficiently larger than losers.

---

# 5. Promotion gates

A strategy starts:
`RESEARCH_ONLY`

Possible lifecycle:
`RESEARCH_ONLY → BACKTESTED → WALK_FORWARD_PASSED → PAPER_LIVE → SMALL_LIVE → STANDARD_LIVE → RETIRED`

No automatic promotion from a single backtest.

Example qualitative requirements:
- positive **net** expectancy in OOS;
- enough independent trades to estimate uncertainty;
- survives stressed costs;
- drawdown within configured budget;
- no single symbol/period accounts for essentially all profit unless intentionally specialized;
- parameter neighborhood is not catastrophically unstable;
- paper/live fills resemble model assumptions.

Exact numeric promotion thresholds must be product-policy configuration, not invented as "industry truth."

---

# 6. Small-account mode

## Objective hierarchy
1. survival / no catastrophic loss
2. collect enough clean live/paper data
3. prove net expectancy after fees
4. scale only when evidence and account size permit
5. compound

## Explicit small-account checks
- `min_order_vs_risk`
- `fees_vs_expected_move`
- `spread_vs_expected_move`
- `max_concurrent_risk`
- `cash_utilization`
- `daily/weekly loss guard`
- `strategy_turnover`

If a €5 minimum trade produces a modeled loss above risk budget, the correct action is **no trade**, not raising risk to make the exchange accept it.

## No "small account magic"
Absolute profit cannot become large quickly without at least one of:
- high return (usually high risk),
- additional deposits,
- leverage (not default/appropriate here),
- time/compounding.

The app should never market compounding simulations as guaranteed growth.

---

# 7. Initial implementation priority

### Phase 1 — foundations
- Kraken/Belgium registry + live fees/minimums
- OHLCV store
- cost model
- risk sizing
- order audit
- walk-forward engine

### Phase 2 — most reproducible source rules
- Brandt classical ATR breakout formalization
- Brandt 3DTSR as management
- CryptoCred S/R first retest
- CryptoCred top-down/FTA
- TCG equilibrium / inside bar
- independent relative-strength filter

### Phase 3 — formalized contextual systems
- bullish BackBurner formalization
- independent Cowen-inspired dynamic DCA
- Loukas-inspired cycle regime
- Krown-inspired trend/volatility/momentum state engine

### Phase 4 — research-only / subjective
- StairStep without full authorized details
- EMA Rider without full authorized guide
- Elliott Wave
- session-gap crypto adaptation

---

# 8. Tests required per strategy

Every detector needs unit tests for:
- positive setup
- near miss
- invalidation
- boundary equality
- missing candles
- duplicated data
- unfinished candle
- insufficient lookback
- fee-tier change
- amount/price precision
- min volume / min cost
- no available cash
- restricted asset
- huge spread
- partial fill
- stop rejection
- restart/recovery.

Golden-test fixtures should store the source rationale and expected state transitions.

# 9. Strategy Context Gate — mandatory extension (2026-08-18)

Before cost/risk/execution, every candidate must pass:

```python
class StrategyContextGate:
    def evaluate(self, strategy, market_state) -> ContextGateResult:
        # required_conditions
        # avoid_conditions
        # market_regime
        # volatility_regime
        # liquidity_requirements
        # timeframe relationship
        # direction/product constraints
        ...
```

Required candidate metadata:

```python
@dataclass(frozen=True)
class UsageContext:
    required_conditions: tuple[str, ...]
    best_conditions: tuple[str, ...]
    avoid_conditions: tuple[str, ...]
    invalid_conditions: tuple[str, ...]
    market_regimes: tuple[str, ...]
    volatility_regimes: tuple[str, ...]
    liquidity_requirements: tuple[str, ...]
    expected_holding_period: str
    source_evidence: tuple[str, ...]
```

A strategy is not considered faithfully implemented merely because its detector finds the visual pattern. The full real-life usage context must be satisfied.

## 10. Source-faithfulness gate

Before an implementation can be named after a creator's actual strategy:

```text
ENTRY RULE KNOWN?          yes/no
INVALIDATION KNOWN?        yes/no
STOP METHOD KNOWN?         yes/no
SIZING METHOD KNOWN?       yes/no
MANAGEMENT KNOWN?          yes/no
EXIT METHOD KNOWN?         yes/no
USAGE CONTEXT KNOWN?       yes/no
NO-TRADE CONDITIONS KNOWN? yes/no
VERSION/SOURCE KNOWN?      yes/no
```

Any unresolved material `no` prevents `SOURCE_FAITHFUL`.

If the creator genuinely leaves a component discretionary, that may still be `SOURCE_FAITHFUL_WITH_DISCRETION`, but the app's machine approximation must be separately identified and testable.

See `STRATEGY_TRUTH_STANDARD.md` for the complete mandatory research and implementation rules.

