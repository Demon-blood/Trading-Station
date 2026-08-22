# Weekly Trading Research — 2026-08-22

## Verified changes

### Benjamin Cowen
Current 2026 primary research memos were found at BenjaminCowen.com. The 15 August 2026 memo uses probability-weighted macro scenarios plus labor, inflation/energy, Fed policy, dollar, liquidity, breadth, BTC long-term moving averages, BTC dominance and composite on-chain risk. At that report date it assigned roughly 65% to a late-cycle base case, 20% to a deeper policy-error correction and 15% to a recessionary transition; it cited BTC dominance near 67% and composite on-chain risk near 0.181.

A July 2026 memo additionally uses the 200-week SMA break/reclaim, cycle duration, on-chain reset state, midterm-year timing and Fed restriction/cooling data to classify Bitcoin as "bottom-watch".

**Truth boundary:** the exact composite-risk formulas remain proprietary/insufficiently disclosed. Do not recreate them from the output values.

### Eric Krown
A current public Long Term Investor (LTI) page describes an 11-component convergence framework:
1. BBWP
2. RSI
3. PMARP
4. Percent-Below-High
5. Krown Cross (21/55 EMA crossover)
6. Pesto Fear & Greed
7. Hash Ribbons (BTC-specific)
8. Weekly/Bi-Weekly EMA
9. Low Month Days
10. Moon Phases (optional)
11. Strong Buy Filter (N-signal convergence threshold)

The components are described as independently toggleable, with private/pre-tuned BTC/SPX/QQQ presets.

**Truth boundary:** exact thresholds, weights and presets are not public. Historical "caught every macro low" claims are creator/backtest marketing, not audited proof.

A current Krown "Operators" page also describes plain-English strategy definition, an audit/advisor layer, guardrails, TradingView webhook execution and activity/decision logging. This is useful architecture, not evidence of alpha.

### Jason Pizzino
A July 27, 2026 video titled **Bitcoin: Tracking The Bull Market Confirmation (New 3-Bar Signal)** was identified. Secondary indexed material describes weekly three-bars-up confirmation, tightening/rounding, structural breakout, volume/range contraction and HH/HL confirmation.

**Truth boundary:** insufficient direct-primary detail was recovered for an exact implementation. It is catalogued only as a blocked research candidate.

### Peter Brandt
No post-17-August strategy-rule change was verified. The August 5, 2026 long-form process/risk interview remains the strongest current implementation source.

### Dan McDermitt / The Chart Guys
No newer source changed the already captured BackBurner, EMA Rider, Equilibrium or Inside Bar core rules.

### CryptoCred
No newly indexed primary long-form material materially changed the existing extracted framework.

### Bob Loukas
No new direct-primary rule change was established. Current 2026 reporting remains consistent with his four-year-cycle framework and current bottom-window thesis. Current price levels are dated forecasts, not timeless strategy rules.

### Alessio Rastani
No newly indexed 2026 direct crypto-strategy rule justified changing the existing Elliott-Wave/probability/opening-gap treatment.

## New trader — Chris Dunn

### Background
First-party current biography says he has 25+ years in markets, traded stocks/e-mini futures full-time from 2007, entered Bitcoin in 2013, founded Wealth Incubator in 2013 and published *How to Trade Bitcoin for Serious Profit* in 2015. Treat these career figures as first-party claims.

### Performance evidence
A 2015 creator-published recap says 19 posted predictions/alerts produced 15 target hits, 2 failures and 2 breakevens; Dunn described that as 88% accuracy excluding breakevens. This is a selected self-published historical record, not an audit. Importantly, the same recap documents losing trades and missed fills.

A student $480→~$50k story exists on his site. It is a testimonial only.

### 1234 Crypto Breakout
Public real-world core:
1. directional run establishes trend;
2. pullback occurs on lower trading volume;
3. price consolidates and retests horizontal resistance;
4. price breaks resistance on high volume.

Usage:
- Dunn explicitly prefers swing trading over day trading;
- source describes days-to-weeks holding;
- prefers fewer, larger opportunities;
- longer consolidation can imply a larger potential breakout;
- meaningful positive news can act as catalyst;
- combines technical, fundamental and sentiment analysis.

Risk/management:
- define stops, position size and targets;
- pursue positive expectancy over a series;
- scale out at predetermined targets, then reassess;
- aim for the meat of the move rather than exact top.

Historical ETH example, not universal:
- entry around $400;
- ~30–35% stop-loss zone;
- initial target around $800;
- later partial exit in the mid-$700s.

**Classification:** `SOURCE_FAITHFUL_WITH_DISCRETION`.
Exact universal volume threshold, consolidation duration, sizing formula and stop formula are not publicly fixed.

## New trader — Josh Olszewicz / CarpeNoctom

### Background
A direct 2020 Set Protocol interview says he entered Bitcoin in 2013, was trading at Techemy at that time, held a Master's in Biotechnology and preferred objective trend-following systems. Current 2026 professional title was not independently confirmed.

### Public footprint
- YouTube / CarpeNoctom
- historical TradingView `IAmSatoshi`
- historical TokenSets Alligator + Fractal Set (GATOR)

A reliable complete GATOR return series was not recovered in this run.

### Crypto Ichimoku
His current-indexed public guide explicitly gives crypto settings:
`20 / 60 / 120 / 30`

It covers nomenclature, bullish/bearish examples, fractals as trailing stops, trend reversal, cloud edge-to-edge trades, a trading checklist and TK lines as oscillators.

Settings alone are not a complete strategy. Each sub-strategy must be extracted separately.

### Alligator + Fractal
Direct Set Protocol interview:
- Alligator = three lower-timeframe moving averages to determine trend;
- Fractal = entries and stop losses;
- philosophy = objective trend following / set-and-forget.

Historical examples attributed to him combine Alligator state, Williams fractal and pattern/cloud/pitchfork context with explicit entry, stop and target zones.

**Truth boundary:** exact Alligator periods/shift configuration for the GATOR Set were not verified. Exact replication remains blocked.

## Belgium / Kraken verification

FSMA re-check confirms the MiCA/CASP post-transition framework remains in force. The Belgian-authorised-CASP list was last verified 14 August 2026 and showed no Belgian-authorised CASPs in that specific list; this does not exclude EEA-passported CASPs. Kraken's official licensing page continues to state Irish MiCA CASP authorisations passported across the EEA.

Kraken's current fee page contains multiple spot schedules:
- standard Tier 1 sections around 0.40% maker / 0.80% taker;
- a Spot Maker Rebate section for eligible lower-liquidity pairs around 0.38% maker / 0.80% taker.

**Implementation consequence:** never hardcode a single Kraken fee. Resolve actual account + pair + product schedule at runtime and persist the schedule used for every backtest/live decision.

Kraken's EEA restrictions page still lists many unavailable assets including USDT, DAI, PYUSD, USDS, USDE, XMR and others. Refresh dynamically.

## Small-account conclusions
- Dunn 1234 is more promising than high-turnover scalping because it seeks larger swing moves and fewer trades.
- Wide stops are acceptable only when position size is reduced enough to preserve EUR risk.
- Olszewicz trend-following may reduce overtrading, but lower-timeframe variants must pass cost tests.
- Krown LTI and Cowen regime logic belong primarily in slow allocation/risk gates.

## Claims explicitly rejected/downgraded
- Krown "caught every macro low" → creator/backtest claim only.
- Krown "winning/proven strategy" marketing → not audited evidence.
- Dunn 88% accuracy → selected self-published historical recap only.
- Dunn student $480→$50k → testimonial only.
- Olszewicz TokenSets performance → not claimed; full reliable series not recovered.
- Pizzino exact 3-bar algorithm → blocked pending direct-primary extraction.
- Cowen composite risk formula → proprietary/unknown.
- Loukas current price targets → dated forecast, not a universal rule.

## Sources added
Primary/official:
- https://benjamincowen.com/reports/the-balance-of-risks-august-2026
- https://benjamincowen.com/reports/bitcoin-cycle-memo-july-2026
- https://krown-trading.teachable.com/p/longterminvestortool
- https://krown-trading.teachable.com/p/cpro
- https://www.chrisdunn.com/about
- https://www.chrisdunn.com/blog/my-crypto-breakout-trading-strategy
- https://wealthincubator.com/the-ultimate-crypto-breakout-trading-strategy-guide-case-studies/
- https://www.chrisdunn.com/a-recap-of-my-bitcoin-trades-predictions/
- https://www.youtube.com/c/carpenoctom
- https://www.fsma.be/en/list/authorised-belgian-crypto-asset-service-providers
- https://www.kraken.com/features/fee-schedule
- https://support.kraken.com/articles/where-is-kraken-licensed-or-regulated

Historical direct interview:
- https://medium.com/set-protocol/how-josh-olszewicz-aka-carpenoctom-a-trader-with-120k-followers-on-twitter-uses-fractals-to-12a35775b1f9
