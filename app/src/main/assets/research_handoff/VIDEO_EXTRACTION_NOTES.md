# Video / Trade-Guide Extraction Notes

This is a **paraphrased implementation extraction**, not a transcript archive. Video titles and IDs are in `VIDEO_RESEARCH_INDEX.csv`.

## Peter Brandt — 2026 Chat With Traders #329

High-value timestamps published by the host:
- ~09:42: took years to become profitable.
- ~22:30: cut losses / let winners run.
- ~31:00: minority of trades generate majority of profits.
- ~36:08: sizing.
- ~43:00: reduce discretionary interference.
- ~48:30: skepticism about optimization.
- ~52:50 onward: horizontal/continuation patterns, indicators and timeframes.
- ~55:53 onward: current rectangle/ADX/MA/ATR example.
- ~1:20:30: assumes each trade can be a loser.

Implementation extraction:
1. **Scan universe on weekly charts.**
2. Candidate must have a recognizable horizontal/continuation pattern.
3. Current example: 8–14 week rectangle/right-angle structure, reasonably compact (~15% band example).
4. Compression filter: ADX(14) below ~12 / near 10 in the example.
5. Trend proxy: 18-day MA.
6. Breakout threshold: boundary +/− roughly 0.5 × ATR(30) in the current described setup.
7. Entry can be a resting stop order.
8. Immediately attach protective risk order when filled.
9. Position size from capital risk and entry/stop distance.
10. Avoid intraday discretionary edits unless the strategy explicitly permits them.
11. Allow rare large winners to dominate expectancy; do not auto-take tiny profit just because it is available.

**Coding caution:** Brandt explicitly exercises chart-pattern craftsmanship. Pattern boundary detection is our formalization.

## Peter Brandt — My Trading Day

First-party process:
- Friday: positions/open orders.
- Weekend: long list → short list → active list.
- Weekly charts select interest; daily charts identify signals.
- For each possible new trade, determine entry, risk and size **before** placing orders.
- Existing positions have target and protective orders reviewed.
- During the week he historically checks periodically, but states intraday actions did not improve his bottom line.
- Daily close has high importance.

App translation:
- `UniverseScanner` → `CandidateList` → `ActiveSetupList`.
- Persist the entire pre-trade plan before execution.
- Never let an execution event exist without a stored invalidation/risk budget.

## CryptoCred — Risk Management / Position Sizing

Canonical implementation concept:
- Decide **money at risk first**.
- Define technical invalidation/stop from setup.
- Compute quantity from risk budget and stop distance.
- Add fees/slippage to the app's own risk math.
- Do not use leverage as a substitute for sizing discipline.

## CryptoCred — Horizontal S/R / Retests

Formalizable core:
1. Identify HTF support/resistance zones/levels.
2. Count repeated same-side tests as a feature; repeated tests can weaken a level.
3. Detect decisive break.
4. Detect first opposite-side retest.
5. Classify:
   - `MICRO_RETEST`: quick LTF return, may not touch exact line, less HTF proof.
   - `ROUNDED_RETEST`: more time/space, visible on HTF, clearer role reversal.
6. Entry still requires the strategy's trigger; "price touched line" is insufficient.

## CryptoCred — First Trouble Area

Never create one generic FTA variable. Store:
- `fta_target`: nearest opposing S/R on thesis timeframe.
- `fta_management`: lower/same timeframe obstacle used for management.
- `fta_entry`: nearby obstacle that must break before a low-conviction entry.
The source explicitly treats these as contextual tools.

## CryptoCred — Previous Day High/Low / Daily Open

PDH/PDL:
- Only meaningful as part of HTF context.
- Sweep/rejection above PDH can support bearish bias; strength below PDL and recovery can support bullish bias.
- Require lower-timeframe confirmation/impulse back through the area.
- For long-only spot, bearish conditions can be an exit/avoid-long signal.

Daily open:
- Treat current day's open as a dynamic reference, **not a magic entry**.
- Crypto implementation must define the day boundary (recommended: exchange/UTC convention stored in strategy config). Never silently use local PC midnight.

## Chart Guys — BackBurner

Public exact-enough concept:
- Strong directional move / higher-timeframe trend.
- Wait for the **first** meaningful lower-timeframe countertrend extreme.
- Example mapping:
  - 5m oversold while hourly trend remains bullish → seek hourly higher low.
  - 1h oversold while daily trend remains bullish → seek daily higher low.
- Public scaling article: tier while RSI remains extreme and continues to become more extreme; after a bounce has occurred, stop adding because the original scaling condition is gone.
- Reverse conceptual direction for overbought countertrend rallies in a larger downtrend.

For Belgium long-only spot:
- Implement bullish continuation side first.
- Bearish version = risk reduction / exit; do not open a leveraged short by default.

**Proprietary boundary:** TCG's paid BackBurner indicator uses multi-timeframe direction, momentum and short-term volatility. Exact formula is not public; do not clone by guessing.

## Chart Guys — Stair Step

Publicly supported facts:
- counter-trend setup
- precise entry location
- clearly defined/tight stop
- works on multiple timeframes
But the public ungated page does not expose every exact condition. Keep detector experimental with parameters visible and call it a `TCG StairStep formalization`, not an exact clone.

## Chart Guys — EMA Rider

Current 2026 guide specifically says EMA12 guides entries/risk and is intended to enter trending moves without chasing the initial breakout.
Public page does not expose all gated conditions. Implement a generic `EMA12TrendPullback` experiment separately unless authorized full guide data is later provided.

## Chart Guys — Equilibrium / Inside Bar

`Equilibrium`:
- lower highs + higher lows = compression.
- breakout of tightening boundaries reveals directional resolution.
- volume often contracts during compression but this is not a universal mandatory condition unless strategy version explicitly says so.

`InsideBar`:
- current bar high <= mother-bar high and current bar low >= mother-bar low (decide equality policy in config).
- breakout direction is information; an order strategy still needs a stop/target/false-break rule.

## Krown — quantitative framework

Public curriculum supports implementing the **architecture**, not copying private formulas:
- trend module
- structure module
- S/R module
- moving-average module
- RSI/momentum
- volatility/expansion
- multi-factor confluence
- strategy definition
- execution/risk
- walk-forward testing
- minimum test-length awareness
- strategy shelf-life monitoring
- date/time and pyramiding controls

`Krown VMP`, Krown proprietary stochastic/fib implementations and template internals stay `PROPRIETARY_UNKNOWN`.

## Benjamin Cowen — Dynamic DCA

Public concept:
1. Quantify broad market risk on a bounded scale/bands.
2. Low risk → larger scheduled allocation.
3. Rising risk → reduce allocation aggressiveness.
4. High risk → scale out according to investor risk tolerance / exit plan.
5. Stick to plan instead of reacting emotionally.

**Do not implement a fabricated ITC risk formula.**
A valid app module is:
`IndependentDynamicRiskDCA`, using a fully documented formula (for example a blend of log-price deviation, MVRV, drawdown, momentum and/or macro features) and explicitly labelled independent.

## Bob Loukas — cycles

Use as slow regime:
- approximately four-year cycle framing.
- ~60-day tactical cycles.
- allocation/cash management and cycle windows.
Do not let a long-cycle model issue precise short-horizon orders by itself.

## Jason Pizzino — swing process

Public guide supports a conventional plan:
`TA thesis → entry → stop → target → management`
plus macro/cycle confirmation.
Exact indicator thresholds were not established strongly enough in the reviewed public source set to claim a single Pizzino algorithm.

## Alessio Rastani — Opening Gap

Original market example is session-based Dow E-mini:
1. mark prior session close.
2. mark current session open.
3. if a material gap exists and conditions support it, trade toward prior close as gap-fill target.
4. risk/stop depends on the strategy and available room.

24/7 crypto has no natural cash open. A UTC/day boundary or weekly boundary implementation is an **adaptation** and must be named as such.

## What was intentionally NOT extracted

- full copyrighted transcripts;
- gated/paid course text;
- proprietary indicator source code;
- exact formulas that a creator does not disclose;
- "secret" settings inferred visually from screenshots;
- testimonials as performance proof.
