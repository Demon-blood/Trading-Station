# M18 — Strategy Truth & Research Validation

M18 builds on merged M17.

## Purpose

M18 removes the remaining gap between a strategy's name and what the software actually
does.

A strategy may participate in AUTO only when:

1. its required data exists in the current engine;
2. its required execution architecture exists;
3. its entry/confirmation/invalidation rules are explicitly defined;
4. its historical validator evaluates those same rules.

A strategy name is no longer allowed to stand in for a rough proxy.

## Truth matrix

### Implemented / live-selectable

#### SCALPING
Defined custom multi-timeframe EMA/OBV momentum scalper.

Requires M5 + M15 + H1 candles and sufficient history on every required timeframe.
It remains a defined application strategy; M18 does not claim that these rules are the
universal definition of scalping.

The current BacktestEngine API accepts one timeframe, so single-timeframe SCALPING
backtests are truth-blocked rather than approximated.

#### TREND
EMA 21/55 trend continuation.

Canonical timeframe: H1.

Long entry requires:
- close > EMA21;
- EMA21 > EMA55;
- positive recent slope/momentum;
- compatible trend regime.

#### BREAKOUT
20-bar prior-resistance breakout with volume confirmation.

Canonical timeframe: M15.

Entry requires:
- close above highest high of the previous 20 completed bars;
- current bar excluded from channel construction;
- current volume >= 1.25x previous 20-bar mean;
- close in upper half of breakout candle;
- compatible regime.

#### REVERSAL
Oversold bullish reversal confirmation.

Canonical timeframe: M15.

Entry requires:
- previous RSI <= 30;
- previous close at/below lower Bollinger band;
- current bullish candle;
- current close reclaims inside lower band;
- incompatible falling/risk-off regimes rejected.

#### MEAN_REVERSION_RSI_BOLLINGER
RSI + Bollinger mean reversion.

Canonical timeframe: M15.

Entry requires prior lower-band/RSI stretch followed by actual re-entry and rising RSI.
A lower-band touch by itself is not an entry.

Primary strategy exit is reversion toward the 20-period basis / invalidation; deterministic
risk stops remain authoritative.

#### VWAP_PULLBACK
Trend VWAP pullback and reclaim.

Canonical timeframe: M15.

Requires:
- EMA21 > EMA55;
- prior pullback to/toward VWAP;
- current VWAP reclaim;
- compatible trend regime.

#### DONCHIAN_BREAKOUT
Explicit 20-bar entry / 10-bar exit variant.

Canonical timeframe: H1.

The current bar is excluded from channel calculation to prevent look-ahead.

#### MOMENTUM_SPIKE_CONTINUATION
Volume-confirmed impulse continuation.

Canonical timeframe: M15.

Requires:
- a separate prior bullish impulse bar;
- large impulse range relative to ATR;
- >=1.5x baseline volume;
- a separate follow-through bar;
- follow-through holding above impulse midpoint and closing above impulse high.

A single large green candle is not called continuation.

### Truth-blocked until required data/architecture exists

#### NEWS_MOMENTUM — DATA_REQUIRED
A true news-momentum strategy requires a timestamped catalyst/event object, recency,
severity/source quality, and observed post-event response. 24h price change is not
substituted for news.

#### RANGE_GRID — ARCHITECTURE_REQUIRED
A real grid needs several resting bid/ask levels, durable grid/inventory state,
re-centering rules, and range invalidation. One near-low BUY is not a grid.

#### MARKET_MAKING_IMBALANCE — ARCHITECTURE_REQUIRED
M16 provides L2 analytics, but true market making requires two-sided quoting, inventory
management, continuous quote amendment/cancellation, fill/queue modelling, and adverse
selection controls.

#### FUNDING_NEWS_RISK_OFF — DATA_REQUIRED
Requires real funding/derivatives/news-risk inputs. A negative spot return alone is not
funding/news risk.

#### PAIRS_RELATIVE_STRENGTH — DATA_REQUIRED
Requires aligned candidate + peer/benchmark return series. A symbol's own absolute return
is not pairs relative strength.

#### DCA_CRASH_PROTECTION — ARCHITECTURE_REQUIRED
Requires durable tranche schedule/state, previous tranche prices, total capital budget,
maximum deployment, and crash guard. A one-time dip BUY is not DCA.

#### VOLUME_ANOMALY_WHALE_MOVE — DATA_REQUIRED
Candle-volume anomalies can be detected, but candle volume cannot identify a whale.
Large-order/order-flow/on-chain evidence is required before that label can be used.

## Monotonic entry authority

M18 closes two independent score-promotion paths.

Before M18 a strategy could return WAIT, but RecommendationEngine could recompute:

score -> SMALL_BUY / BUY

AI could then independently do the same.

M18 changes the authority chain to:

strategy truth rule
-> strategy entry permission
-> RecommendationEngine may confirm/reduce but cannot create permission
-> AI may veto/reduce but cannot create permission
-> deterministic risk/execution/economics gates remain final

Important examples:

WAIT + score 95 -> never BUY

SMALL_BUY + high AI score -> at most SMALL_BUY

BUY + adverse AI/news/risk evidence -> may become SMALL_BUY/WAIT/AVOID

## AUTO behavior

AUTO only ranks StrategyTruthRegistry entries whose `liveSelectable` flag is true.

CloudShare collective evidence is only a tie-break hint after truth-valid candidates
exist. It cannot make a truth-blocked strategy eligible.

## Backtest truth

BacktestEngine uses the same StrategyTruthRules as live strategy selection.

Historical-validation rules include:

- unsupported strategies fail with `TRUTH_BLOCKED`;
- AUTO cannot be backtested as if it were one strategy;
- canonical timeframe is enforced;
- multi-timeframe SCALPING cannot be validated with the single-frame API;
- signal generated from a completed bar enters at the NEXT bar open;
- same-bar target + stop ambiguity resolves conservatively to the stop;
- strategy invalidation exits at the next bar open;
- baseline maker fees are charged on entry and exit;
- positive net return is required in addition to the existing trade-count, win-rate,
  profit-factor and drawdown gates.

The baseline fee is only a conservative strategy-validation assumption. Production trade
economics remain under M5 and the later M20 cost optimizer.

## No Room migration

M18 does not change the Room schema. Database version remains 12.

## Run

Commit the M18 bootstrap contents to `main`, then run:

Actions
-> M18 Strategy Truth & Research Validation
-> Run workflow
-> main

Successful branch:

`milestone/m18-strategy-truth-<run-number>`

The workflow runs M18 and every prior milestone verifier, then Kotlin compile, all unit
tests, APK assembly, controlled diff, branch creation and PR creation.
