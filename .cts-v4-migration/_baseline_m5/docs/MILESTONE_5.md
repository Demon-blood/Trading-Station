# Milestone 5 — Research + Strategy/AI Expansion

M5 ports the desktop v1.0.50 research/ensemble layer into native Kotlin while retaining M3/M4 safety and execution ownership.

## Expanded research strategies

The research vote engine evaluates 23 desktop-derived families:

- VOLATILITY_BREAKOUT
- PULLBACK_CONTINUATION
- MEAN_REVERSION
- VWAP_RECLAIM
- LIQUIDITY_SWEEP_REVERSAL
- BOLLINGER_SQUEEZE_BREAKOUT
- EMA_TREND_RIDER
- RSI_DIVERGENCE_REVERSAL
- SUPERTREND
- SUPPORT_RESISTANCE_BOUNCE
- DONCHIAN_CHANNEL_BREAKOUT
- KELTNER_CHANNEL_BREAKOUT
- MACD_TREND_CROSS
- STOCHASTIC_OVERSOLD_REVERSAL
- CCI_MEAN_REVERSION
- ADX_TREND_PULLBACK
- PARABOLIC_SAR_FLIP
- ICHIMOKU_CLOUD_BREAKOUT
- ROLLING_RANGE_EXPANSION
- VWAP_DEVIATION_REVERSION
- NEWS_MOMENTUM_CONFIRMATION
- SPREAD_LIQUIDITY_SCALP
- MULTI_TIMEFRAME_CONFIRMATION

These are research/ensemble votes. They do not bypass the existing Android strategy, execution or risk system.

## Research validation

- AdvancedRegimeEngine classifies trend/range/volatility/risk-off context.
- WalkForwardMonteCarloEngine evaluates realized strategy/symbol history with rolling walk-forward and deterministic bootstrap Monte Carlo tests.
- MetaModelDecisionEngine promotes/penalizes only when enough closed outcomes exist.
- CrossSymbolIntelligenceEngine uses BTC/ETH broad context for confirmation.
- StrategyMutationLab generates bounded built-in variants rather than arbitrary executable code.
- AutonomousHypothesisEngine proposes bounded score hypotheses from realized history.
- ParameterOptimizerEngine emits conservative suggestions; it does not rewrite live parameters automatically.
- SequenceAndRlResearchEngine contains a small persistent sequence scorer and an RL sandbox whose state is research-only.
- Order-book replay is research-only.

## External/context intelligence

Kraken Futures context is public/read-only and never places futures orders. Labeled-wallet intelligence is optional and neutral when no Whale Alert key is configured. Cross-market reference checks are public/read-only; Kraken remains the execution venue.

## Persistence

Room v11 adds:

- `research_events`
- `research_strategy_profiles`
- `research_state`

Migration `10 -> 11` is explicit and non-destructive.

## CloudShare

M5 emits the existing desktop/Worker aggregate families:

- `shared_research_daily`
- `shared_strategy_variant_daily`
- `shared_walk_forward_daily`
- `shared_onchain_daily`

No API tokens or local secret material are included in these aggregate payloads.

## Live safety boundary

Research adjustments are bounded by default to `-8..+6`. Research-generated LIVE entries are disabled by default. Existing SELL decisions are preserved exactly at the research boundary. M3 governance runs after M5, and M4 owns final sizing/order selection.
