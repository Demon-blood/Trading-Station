# Strategy Truth Standard

**Added:** 2026-08-18  
**Applies to every current and future trader/strategy in this handoff.**

## 1. Core rule

The app must implement the **real strategy as actually taught/used**, not a simplified label or a loose interpretation.

For a strategy to be classified as `SOURCE_FAITHFUL`, research must establish, as far as publicly verifiable:

1. **Purpose** — what problem/opportunity the strategy is meant to exploit.
2. **Market type** — spot, futures, options, equities, session market, 24/7 crypto, etc.
3. **Direction** — long, short, both, allocation-only, exit-only, hedge, etc.
4. **Market regime** — trend, range, compression, expansion, bull, bear, high/low volatility, cycle stage.
5. **Timeframes** — context timeframe, setup timeframe, execution timeframe, management timeframe.
6. **Universe selection** — what instruments/assets qualify and why.
7. **Preconditions** — everything that must already be true before a setup is valid.
8. **Setup definition** — exact observable structure/indicator/state.
9. **Trigger** — the event that converts a setup into an actionable signal.
10. **Entry method** — market, limit, stop, retest, scale-in, DCA, resting order, etc.
11. **Confirmation** — if required, exactly what confirms the entry.
12. **Invalidation** — what makes the thesis wrong.
13. **Initial stop** — exact or sourced method for choosing it.
14. **Position sizing** — fixed size, risk-based size, volatility size, scaling, campaign risk, etc.
15. **Leverage/margin** — if the original strategy uses it, document it faithfully, but do not enable it in Belgium spot mode unless separately legal/supported.
16. **Trade management** — trailing stop, breakeven logic, scale-ins, scale-outs, re-entry, pyramiding, time stop, etc.
17. **Profit-taking** — targets, measured moves, R multiples, structural levels, partial exits.
18. **Exit rules** — target, stop, invalidation, time, opposite signal, regime change.
19. **Re-entry rules** — whether a failed first attempt may be retried and under what conditions.
20. **No-trade conditions** — when the creator says the setup should be ignored.
21. **Frequency / holding period** — typical cadence when stated.
22. **Fees/liquidity assumptions** — especially where the original venue/cost structure differs from Kraken.
23. **Examples** — enough real examples to test that the written rule matches how the trader actually applies it.
24. **Method evolution** — older and newer versions are separate when rules changed.
25. **Source provenance** — direct video/article/course/interview timestamps wherever possible.
26. **Known discretion** — human judgement that cannot be reduced to a public exact rule.
27. **Known proprietary components** — private indicator/formula/settings.
28. **Failure modes** — documented losing conditions, drawdowns, failed calls, false breakouts, whipsaws, etc.
29. **Empirical evidence** — independent validation, creator-only claim, anecdotal evidence, or none.
30. **Implementation adaptation** — anything changed to fit Belgium/Kraken must be explicitly labeled as an adaptation, never attributed to the creator.

If any material component above is unknown, store it as `UNKNOWN`/`UNVERIFIED`; **do not infer a hidden rule and present it as fact.**

---

## 2. Fidelity states

### `SOURCE_FAITHFUL`
The publicly available evidence is sufficient to reproduce the strategy's real observable decision process with low ambiguity.

### `SOURCE_FAITHFUL_WITH_DISCRETION`
The real strategy is documented, but one or more decisions genuinely rely on trader judgement. The app may formalize those decisions only if:
- the formalization is visible,
- parameters are exposed,
- it is labeled as a machine formalization,
- it is validated independently.

### `FORMALIZED_FROM_PUBLIC_CORE`
The creator's core idea is public but exact implementation details are missing/gated/private. The app may research an independent formalization, but must **not** call it the creator's exact strategy.

### `CONCEPT_INSPIRED`
Only the broad concept is public. The resulting algorithm is ours.

### `PROPRIETARY_NOT_IMPLEMENTED`
Exact formula/rules are not public enough to reproduce truthfully.

---

## 3. “When to use it” is mandatory

Every executable strategy must provide a `usage_context` object:

```json
{
  "best_conditions": [],
  "required_conditions": [],
  "acceptable_conditions": [],
  "avoid_conditions": [],
  "invalid_conditions": [],
  "market_regime": [],
  "volatility_regime": [],
  "liquidity_requirements": [],
  "timeframe_relationship": {},
  "direction_policy": "",
  "expected_holding_period": "",
  "source_evidence": []
}
```

A detector may return a technically valid pattern but the **Strategy Context Gate** must reject it if its real usage conditions are absent.

Examples:
- A BackBurner-style continuation setup should not fire just because RSI is oversold; it requires the larger directional move/trend and the relevant first countertrend reaction.
- A Brandt breakout should not fire from any two horizontal lines; it requires a qualified/mature pattern and the strategy-version conditions.
- A Cowen-style risk allocation concept is not an intraday entry trigger.
- A Loukas cycle model is not a 5-minute scalp signal.
- A session opening-gap strategy cannot be silently transplanted into 24/7 crypto without declaring a new adaptation.

---

## 4. Do not collapse strategy components

If a trader uses multiple layers, preserve them separately:

`UNIVERSE → CONTEXT → SETUP → TRIGGER → ENTRY → RISK → MANAGEMENT → EXIT`

Examples:
- CryptoCred `S/R level` is context/setup; `retest` is setup; `entry trigger` is separate; `FTA` is management/target context.
- Brandt `pattern identification`, `ATR-standardized breakout`, `Last Day Rule`, and `3DTSR` are separate components/tactics.
- Chart Guys `BackBurner`, `Equilibrium`, `Inside Bar`, `EMA Rider`, and `Stair Step` are separate strategies.
- Cowen `BTC dominance`, `Price Risk`, and Dynamic DCA are distinct concepts.
- Krown's standard public indicators must not be conflated with proprietary VMP/template logic.

---

## 5. Strategy versioning

Every strategy has:
- `source_first_seen`
- `source_last_verified`
- `method_version`
- `rule_change_log`
- `retired_or_superseded`
- `superseded_by`

If a trader changes:
- timeframe,
- indicator length,
- stop logic,
- entry method,
- risk,
- target,
- market type,
- or preferred trading style,

create a new strategy version unless the source explicitly says the old and new rules are equivalent.

---

## 6. Implementation adaptation policy

When converting a real-world strategy to Kraken/Belgium:

### Preserve
- causal idea
- setup geometry/state
- confirmation logic
- invalidation logic
- risk logic
- management sequence
- timeframe relationships

### Adapt only when necessary
Examples:
- futures short → `EXIT/REDUCE/AVOID_LONG` in long-only Belgium spot profile;
- BTC/USDT → permitted/liquid BTC/EUR market;
- session open → cannot be called exact in 24/7 crypto; create separate adaptation;
- broker-native stop order → equivalent Kraken/app-side conditional execution with explicit latency/fill model.

Every adaptation must declare:
- what changed,
- why,
- legal/platform reason,
- expected behavioral difference,
- whether creator fidelity was reduced.

---

## 7. Research evidence required before live implementation

Before a strategy leaves `RESEARCH_ONLY`, the research record must include:

- at least one direct primary explanation of the method;
- preferably multiple direct examples/trade walkthroughs;
- timestamps or exact source sections for material rules;
- any contradictory versions reconciled/versioned;
- no unresolved unknown in entry, invalidation, sizing, or exit that is silently guessed;
- realistic Kraken fee/slippage model;
- out-of-sample/walk-forward testing of **our implementation**;
- paper/live verification that machine detections resemble source examples.

If exact public evidence is insufficient, the strategy may still be researched as a formalization but cannot receive the `SOURCE_FAITHFUL` badge.

---

## 8. Research coverage for every person

For **every current or newly discovered trader**, research:

### Identity / background
- full/public identity where known;
- career history;
- institutional/professional history;
- education/credentials where relevant;
- companies/funds/exchanges/platforms used;
- conflicts of interest, paid products, referrals/sponsorships.

### Performance / success
- audited track record if any;
- public portfolios/model portfolios;
- verifiable calls with original timestamps;
- reported success stories clearly labeled by evidence class;
- CAGR, drawdown, win rate, profit factor, etc. only if actually supported.

### Failure / criticism
- losing years/drawdowns;
- failed calls;
- blown accounts/liquidations if credibly documented;
- retracted/changed methods;
- controversies relevant to credibility;
- survivorship/selection bias risks.

### Trading
- every publicly documented strategy;
- every variation/version;
- indicators and settings;
- timeframes;
- markets/pairs;
- venues/platforms;
- exact setup/entry/exit/risk/management;
- when and when not to use;
- examples/live trade walkthroughs;
- automation/alert tools;
- data feeds or public platform data.

### Suitability
- Belgium legality/product availability;
- Kraken compatibility;
- small-account feasibility;
- fee/slippage sensitivity;
- minimum-order/risk interaction;
- likely turnover and capital requirements.

---

## 9. Small-account rule

Research must never equate “growing a small account” with taking uncontrolled risk.

For each strategy calculate/test:
- minimum economically sensible position size;
- fee break-even move;
- spread/slippage break-even;
- expected net R after cost;
- minimum account size required to obey risk rules;
- whether exchange minimum forces excess risk;
- turnover drag;
- worst historical losing streak/drawdown where measurable.

If a strategy cannot be used honestly on a small account after costs/minimums, mark it:
`NOT_SMALL_ACCOUNT_SUITABLE_AT_CURRENT_FEE_TIER`.

---

## 10. Truth hierarchy

When sources conflict:

1. Official regulator/exchange docs for law/platform facts.
2. Trader's direct current explanation for what they currently teach/use.
3. Trader's older direct explanation for historical versions.
4. Independently audited performance evidence.
5. Direct live-trade examples.
6. Reputable interviews/secondary analysis.
7. Community summaries.
8. Marketing/testimonials.

Never use lower-level evidence to overwrite stronger evidence without stating the conflict.
