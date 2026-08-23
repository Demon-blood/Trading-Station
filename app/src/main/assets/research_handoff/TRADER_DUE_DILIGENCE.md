# Trader Due Diligence

Research freeze: **2026-08-17**

## 1. Peter Brandt

### Background
- Entered the commodity trading business in 1976 at ContiCommodity Services.
- Founded Factor Trading in 1980 and has traded proprietary capital across futures/forex and other markets.
- His public material is built around classical charting, risk management, trading process and trader psychology.
- A 2026 Chat With Traders interview describes roughly a 50-year career and contains unusually detailed current process/risk statements.

### Performance evidence
Brandt's own site states that an independent audit of his 1981–2010 record produced approximately **41.6% CAGR**, with four losing years averaging about -4.7% and a worst year around -8.4% for that stated period. This is much stronger evidence than screenshots/testimonials, but this package has **not independently inspected the underlying auditor workpapers**, so the correct wording is: **"Brandt states that an independent audit found…"**

Do not apply the historical return to a Belgium spot-only implementation: his historical business included futures and leverage, while the app's default scope is unlevered spot.

### Publicly observable method
**Identification**
- Weekly charts for trade identification; daily charts for timing.
- Strong preference for clean horizontal/continuation patterns.
- Current 2026 interview example: rectangle/right-angle structure roughly 8–14 weeks, approximately within a 15% range.
- Historical Factor documents often use broader windows such as 10–26 weeks. These are **versioned differences**, not numbers to average together.
- He dislikes relying on indicators; however he explicitly uses **14-day ADX** for compression, **18-day moving average** as trend proxy, and **30-day ATR** to standardize breakout distance.

**Current 2026 example**
- ADX(14) below ~12 / near 10 = desired compression condition in his example.
- Breakout standardization: about **50% of 30-day ATR** beyond the pattern boundary.
- Entry via resting stop order.
- Protective contingent order activates immediately with the entry.
- He wants the market to reveal quickly whether the trade is working.

**Risk/process**
- Position size derives from entry-to-stop distance and capital-at-risk, not from conviction alone.
- Historical public material commonly discusses max nominal risk around 1% per trade; a CFA interview described ~80–100 bps per bet and ~200 bps for correlated aggregate exposure.
- Public Factor material stresses getting risk toward breakeven quickly when price permits.
- He reports that roughly 20% of trades account for at least ~80% of profit; therefore cutting winners too quickly damages the payoff distribution.
- Current interview: he tracks **expected value** and **profit factor** and regards outcome obsession as secondary to process.
- Current process is low frequency: weekly planning, resting orders, daily review; he says intraday tinkering did not add to his bottom line historically.

**3-Day Trailing Stop Rule (3DTSR)**
- A historical tactical rule used to exit a losing position quickly, tighten risk as a move matures, or occasionally enter/add during a correction.
- Directional concept:
  - after an upswing, identify the recent highest day; a later close below that high-day's low creates a setup; penetration of the setup day's low is the downside trigger;
  - reverse the logic after a downswing.
- Brandt also states the rule may be used once a trend has travelled roughly 70% of its expected distance.
- **Important:** 3DTSR is a tactical overlay, not the main pattern-identification system. Preserve the historical-version context.

**Last Day Rule / stop handling**
- Public 3DTSR material says that for a long breakout, if at least half of the breakout bar is below the boundary, its low can define the Last Day Rule stop; otherwise the last full day inside the pattern may be used.
- Brandt later described seeking tighter risk in some cases, including reference to the breakout-day open.
- This is a **versioned discretionary tactic**, not a universal fixed stop formula.

### Platforms
Brandt's own platform article has identified:
- Trade Navigator for charting/order entry.
- CQG Trader for some order routing.
- TradingView as a charting tool while away from the office, not his main execution venue.
- Historical crypto venues he named included itBit/Paxos, Coinbase/GDAX and Binance, primarily for buying BTC; he stated he did not short crypto or buy crypto with leverage in that older platform note.

### Failures / limitations that matter
- He explicitly discusses long losing streaks and drawdowns. A 2026 interview discusses being wrong 19 of 21 trades and the necessity of allowing the next winners to run.
- Classical pattern identification contains human judgement: "clean", "well-defined", boundary placement, false-breakout interpretation.
- Therefore a coded Brandt detector is always a **formalization of his published criteria**, not "Peter Brandt's exact brain".
- His own current comments are skeptical of parameter optimization. Our backtesting is validation of our formalization, not a claim that Brandt optimizes this way.

### Small-account relevance
Very high for **risk architecture**, but actual long-duration patterns may offer relatively few signals. This is desirable when Kraken fee tiers make high-turnover scalping expensive.

---

## 2. CryptoCred

### Background
CryptoCred is a pseudonymous trading educator with a deliberately limited public personal profile. His public technical-analysis series is unusually process-oriented.

**I cannot confirm an independently audited personal P&L or a conventional professional-trading résumé.** Do not convert educator quality into a claim of verified profitability.

### Public curriculum / method
Public videos/articles cover:
- candlesticks and candlestick highs/lows
- horizontal support/resistance
- market structure
- timeframes and top-down analysis
- retests
- entry triggers
- risk management / position sizing
- trade management
- "first trouble area" (FTA)
- pattern failure
- previous-day high/low and daily-open intraday context
- market microstructure and order types
- RSI, Fibonacci, Ichimoku and other tools as supporting material

### High-value mechanics

**Position sizing**
Core principle:
`position size = money prepared to lose / distance from entry to stop`
(adjust units, fees and slippage in the implementation).

Leverage does not define economic risk; stop distance and size do. For this app, default live scope remains unlevered spot.

**Horizontal S/R**
- Higher-timeframe levels carry more weight.
- Repeated tests from the same side can weaken a level.
- A broken level may flip role.
- The **first meaningful retest from the opposite side** after a break is a recurring high-value setup.
- Public material distinguishes rapid/micro retests from rounded retests with more time/space. Rounded retests provide clearer higher-timeframe evidence; micro retests are more aggressive.

**Top-down approach**
- Use higher timeframe to establish structure/context.
- Use a lower execution timeframe for trigger/entry only after the larger context has been established.
- Do not let tiny lower-timeframe noise prematurely invalidate a higher-timeframe trade thesis.

**First Trouble Area**
Three distinct uses must not be mixed:
1. **Profit-taking FTA:** nearest opposing S/R on the trade-idea timeframe.
2. **Management FTA:** often one timeframe lower, used to make management decisions.
3. **Entry FTA:** in crowded/low-conviction contexts, require a nearby opposing obstacle to break before entry.
The exact timeframe mapping remains contextual; public examples include Monthly→Weekly, Weekly→Daily, Daily→H4/H1 management.

**PDH/PDL intraday bias**
Public material uses previous-day high/low in conjunction with Daily/HTF S/R and dominant structure:
- bearish opportunity can arise from weakness/rejection after trading above previous-day high;
- bullish opportunity can arise from strength after trading below previous-day low.
The sweep alone is **not** the complete signal; context and lower-timeframe confirmation matter.

**Market microstructure**
- Limit orders specify worst acceptable price but do not guarantee execution.
- Market orders prioritize execution, not exact fill.
- Spread/liquidity/order-flow considerations are part of execution quality.
This matters directly to a small Kraken account.

### Failure/uncertainty
- Most CryptoCred rules are frameworks/guidelines, not a single closed-form system.
- Several decisions remain discretionary: exact S/R placement, strength/weakness, impulse quality, whether a retest is "good", and timeframe selection.
- No audited P&L was found.

### Small-account relevance
Excellent educational fit because it naturally enforces defined invalidation and risk-based sizing. Cost-aware execution must be added for Kraken.

---

## 3. Dan McDermitt / The Chart Guys

### Background
- The Chart Guys' current biography says Dan has traded since 2010 and has 14+ years of experience.
- He trades volatile/liquid markets across crypto, equities and other sectors.
- His current biography identifies the **oversold bounce** as a signature setup.
- The site documents evolution: after years of aggressive day trading, his process moved toward more swing-oriented trading. Historical videos must therefore stay versioned.

### Success evidence
A hosted interview is titled around turning roughly **$3,000 into full-time day trading**. Treat this as a reported personal success story, **not an independently audited brokerage record**.

No independently audited long-term P&L was found.

### BackBurner
Current first-party description:
- exploit the **first major counter-trend reaction** following a significant directional move;
- seek a short-term extreme that can form a higher-timeframe higher low / lower high;
- combines multi-timeframe direction, directional momentum and short-term volatility;
- optimized by TCG for 5-minute and hourly usage:
  - first 5m oversold condition may correspond to an hourly higher low;
  - first hourly oversold condition may correspond to a daily higher low.
A first-party scaling article adds a practical long example: use 5m oversold in an hourly uptrend, tier only while RSI remains extreme/more extreme; **once a bounce occurs, the condition for adding is gone**.

The **commercial indicator formula is proprietary**. The underlying strategy concept is public; do not claim an exact clone of the indicator.

### Stair Step
- Counter-trend technique designed to identify a precise location with a tight, objectively defined stop.
- Applicable across timeframes/sectors.
- Public metadata is clear on purpose but does not expose every exact calculation; code as fidelity B unless a complete authorized guide is supplied.

### EMA Rider (current 2025/2026 method)
- Current guide explicitly uses **EMA12** as a structure/momentum guide.
- Purpose: avoid chasing initial breakout and enter a trend on structured pullbacks/retests, manage risk and remain with momentum.
- The free e-book is gated, so public metadata does not expose all exact entry/exit conditions. Fidelity B/X for unpublished specifics.

### Equilibrium / inside bar
- Equilibrium: tightening range with lower highs/higher lows; use break of the tightening structure to reveal direction.
- Inside bar: current bar/range stays inside the preceding "mother" bar; lower timeframe often appears as tightening higher lows/lower highs; high/low break provides directional information.
- These are mechanically detectable, but **trade entry/stop/target is a separate strategy decision**.

### Correlation / relative strength
Use a correlated benchmark to choose stronger long candidates or weaker short candidates. For Belgium long-only spot: relative weakness can mean avoid/reduce rather than short.

### Risk-free trade / scaling
Dan has a public guide specifically teaching scale-outs across three areas to create a "risk-free" residual position. The public page describes the concept but not all gated details. In code, never call a position literally risk-free: exchange gaps, slippage, failures and operational risks remain. Better UI label: `principal/profit protected by realized exits` when mathematically true.

### Platforms/data
- TCG's commercial indicators are explicitly made for **TradingView** and support TradingView alerts.
- Their crypto alert product says it quantifies/codifies conditions Dan uses and analyzes real-time exchange data.
- This confirms useful data categories, but proprietary indicator internals are not public.
- Do not infer his personal broker/exchange for every trade from a chart screenshot.

### Small account
Very relevant tactically, but Kraken Tier-1 fees can make 5-minute scalps economically poor. BackBurner concepts on hourly/longer timeframes deserve testing before short-horizon variants.

---

## 4. Eric "Krown" Crown

### Background
Krown's current first-party course/channel biographies describe him as a former/licensed equity-options market maker and a professional trader/investor. This is primarily **self-reported first-party biography** in the sources reviewed. No independently audited personal P&L was found.

### Public framework
Krown's public curriculum is highly compatible with modular quantitative architecture:
- trend identification
- market structure
- support/resistance
- moving averages
- RSI
- volatility / volatility expansion
- confluence
- system construction
- execution framework
- risk discipline

His Quant curriculum further exposes modules around:
- range-high/low detection
- Krown Cross moving averages
- BBWP
- RSI
- PMARP
- stochastic momentum
- proprietary VMP / stochastic / Fibonacci tools
- pyramiding
- date/time filters
- backtest results
- minimum testing periods
- strategy shelf-life
- forward-walk testing
- parameter/entry/exit optimization and deeper statistics

### Proprietary boundary
Named products such as **Krown VMP**, Krown Stochastic/Fibonacci implementations and some strategy templates are proprietary. Public marketing explains their role (volatility/momentum/statistical state, multi-timeframe behavior, etc.) but not enough to truthfully reproduce every formula/setting.

Policy:
- implement standard published indicators such as RSI/EMA/BBWP/PMARP from their canonical definitions if desired;
- label them `generic/public indicator`;
- never call an independently reconstructed oscillator "Krown VMP exact".

### Platform
Quant Prime publicly lists TradingView-based strategy construction and automation integrations such as Alertatron, 3Commas and PineConnector. Its published supported execution list contains a number of centralized exchanges; **Kraken was not listed in the source reviewed**. Therefore translate strategy logic into the app's own Kraken execution layer rather than pretending native Krown automation support.

### Small account
High value as a **system-engineering methodology**: state classification, out-of-sample testing, walk-forward evaluation and explicit costs are more useful than blindly copying a high-frequency template.

---

## 5. Benjamin Cowen

### Background
Public biography/education pages describe:
- PhD-level engineering/nuclear-engineering education.
- Research/work background including Sandia National Laboratories and prior research exposure associated with NASA.
- Founder of Into The Cryptoverse (ITC).

### Style
Cowen is better classified as a quantitative/macro crypto analyst and allocator than a high-frequency execution trader.

### Public methods/data
ITC exposes:
- Price Risk analysis
- DCA simulator
- exit-strategy tools
- Risk Portfolio Tester
- modern portfolio tools/workbench
- MVRV Z-score
- ROI after cycle bottoms
- short-term bubble-risk views
- Bitcoin dominance
- inflation / interest-rate context
- logarithmic regression bands
- long-term moving averages
- custom TradingView indicators

Public videos include:
- Bitcoin Entry/Exit Strategies
- Dynamic DCA
- Bitcoin Risk Metric
- on-chain cycle risk

### Dynamic DCA
Public concept:
- use a risk band/state rather than an all-in/all-out call;
- allocate **more aggressively at lower risk** and less aggressively / scale out as risk rises;
- choose entry/exit behavior consistent with investor risk tolerance and follow a pre-defined plan.

### Proprietary boundary
The exact current **ITC Price Risk** formula and all parameterization are not publicly established in the reviewed sources. Do not infer it from chart appearance.

Allowed implementation:
`Independent Risk Band Model inspired by dynamic-risk allocation`
with its own documented formula and no claim of being Cowen's metric.

### BTC dominance / macro regime
Good candidate for a regime engine:
- BTC.D trend/state can affect whether the system prefers BTC vs alt exposure.
- inflation, interest rates, liquidity and broad risk assets can be contextual features.
These are contextual models, not stand-alone proof of entry edge.

### Performance
No independently audited personal brokerage record was found. Prediction scorecards from Reddit or cherry-picked social posts are not sufficient evidence either for or against lifetime profitability.

### Small account
Dynamic DCA/position scaling can fit a small account, provided Kraken minimums and fees do not force trades too small to be economical.

---

## 6. Bob Loukas

### Background
Loukas publicly describes himself as a position trader with roughly 30 years of experience and is best known in crypto for cycle analysis.

No audited personal P&L was found.

### Methods
- multi-year / approximately four-year Bitcoin cycle framing;
- shorter ~60-day cycle work for tactical positioning;
- long-horizon allocation and emotional discipline;
- model-portfolio updates rather than high-frequency signal calls.

### Success/failure evidence
The 2018/2019 cycle material became well known for identifying the 2019 region as a major cycle opportunity. That is a historical call/model observation, **not an audited return**.

A public/secondary review of the prior cycle notes that cycle timing was materially better than an extremely bullish ~$150k–$200k price target. This is a useful example of why **timing framework and price target must be evaluated separately**.

Recent 2026 reporting describes his public model portfolio re-entering BTC around the mid-$60k region while reserving cash for lower levels. A public model is useful transparency but is not identical to a verified personal brokerage account.

### Implementation
Use Loukas primarily as:
- long-term regime state
- allocation aggressiveness
- cycle-window feature
not as a tick-level entry engine.

Any exact cycle-date window or moving-average threshold should be versioned to its source date because cycle models evolve.

---

## 7. Jason Pizzino

### Background
Pizzino's public biography describes long experience across crypto, stocks/commodities and property/market cycles. This is largely first-party/self-reported.

No audited personal trading record was found.

### Public method
Public swing-trading guide explicitly covers:
- technical-analysis setup
- trading plan
- entry
- stop loss
- profit targets

Broader current content emphasizes:
- market cycles
- higher highs / higher lows
- confirmation rather than exact-bottom catching
- percentage returns instead of absolute-price fantasies
- macro/economic/property cycles
- investor psychology

### Implementation
Good inputs for:
- market-structure / swing regime
- cycle confirmation
- avoiding counter-cycle trades
But the public material reviewed does not justify inventing one fixed "Pizzino algorithm".

### Small account
Swing-horizon execution is more compatible with high low-volume Kraken fees than rapid scalping, but each formalized rule still needs net-of-cost validation.

---

## 8. Alessio Rastani

### Background
Rastani has publicly operated as a trader/market commentator and technical-analysis educator for many years. His current material emphasizes probabilities, trend/path of least resistance, risk and Elliott Wave.

A 2011 BBC appearance generated controversy; subsequent reporting investigated whether he was a hoax. He publicly described himself as an independent trader of his own account rather than an institutional representative. That episode is relevant background but not itself proof of profitability or fraud.

No audited long-term P&L was found.

### Elliott Wave
Core framework:
- impulsive and corrective wave structures
- Fibonacci relationships
- trend/context and probability
The main implementation problem is **non-unique wave counts**: multiple plausible labels can exist simultaneously. A deterministic wave counter should not be marketed as "exact Rastani Elliott Wave" unless exact rules are supplied.

### Opening Gap live example
A first-party LeadingTrader example demonstrates a non-crypto intraday method:
- Dow E-mini, 5m/2m examples
- define previous session close
- compare current session open
- trade toward the prior close as a potential gap fill
- stop/management depends on the strategy and available room
The source mentions common hard-stop/target conventions used by some traders, but these are **not proven to be Rastani's universal fixed parameters**.

For 24/7 crypto, adapting a cash-session opening gap requires a deliberately chosen crypto session boundary. That would be **our adaptation**, not the original strategy.

### Small-account fit
Low priority for direct implementation. Elliott Wave can serve as optional context; the opening-gap idea belongs in a research sandbox, not the first live Kraken strategy set.
