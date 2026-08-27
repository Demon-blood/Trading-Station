# Crypto TradeStation M9 — Champion / Challenger Strategy Governance

M9 separates **research quality** from **production authority**.

Before M9, a strong research vote could promote a WAIT/WATCH decision into a
SMALL_BUY. Handoff strategies already had empirical gates, but there was no
persistent cross-strategy champion/challenger comparison.

M9 adds that missing layer.

## Production model

```text
PAPER
  challenger trials allowed
       ↓
exact strategy-tagged realized exits
       ↓
chronological OOS holdout
       ↓
walk-forward + Monte Carlo
       ↓
multi-regime evidence
       ↓
champion/challenger comparison
       ↓
LIVE research promotion authority
```

Normal deterministic decisions and all later M4/M5/risk gates remain authoritative.

Protective EXIT/REDUCE actions are not champion-gated.

## Initial champion gate

A strategy can become the first champion for a symbol only when all conditions pass:

- >= 30 exact strategy-tagged realized SELL outcomes
- >= 20 PAPER outcomes
- >= 10 chronological OOS outcomes
- >= 7 days between first and latest retained outcome
- >= 2 observed market regimes
- conservative OOS net P/L >= 0.25 quote currency
- mean conservative OOS return >= 5 bps per outcome
- lower 95% OOS return bound > 0
- OOS profit factor >= 1.20
- OOS normalized drawdown <= configured max drawdown
- walk-forward status PASS, score >= 60, >= half windows profitable
- Monte Carlo score >= 60
- Monte Carlo P(positive) >= 65%

There is no fallback from another strategy's outcomes. Exact attribution is mandatory.

## Conservative cost treatment

Closing trade P/L already includes the observed exit fee when Crypto TradeStation
calculates it locally. M9 subtracts one additional observed-fee amount from the
closing outcome as a conservative reserve for the entry-side fee that is not stored
on that closing TradeEntity.

This makes promotion harder rather than giving challengers free fee assumptions.

## Replacement challenger gate

A challenger must first pass every standalone gate above.

It must then beat the current champion with:

- mean normalized OOS improvement >= 5 bps
- lower 95% challenger-minus-champion return bound > 0
- no profit-factor regression
- drawdown no worse than the bounded champion allowance
- no material walk-forward/Monte-Carlo regression

A seven-day promotion cooldown prevents strategy churn.

## PAPER vs LIVE

PAPER research/handoff trials remain eligible under the existing paper gates so a
challenger can gather evidence.

LIVE positive research/handoff promotion requires:

`strategyGovernance.productionAuthorized == true`

That is true only when:

- the selected strategy is the persisted champion, or
- the challenger has just passed a valid promotion.

M9 does not block protective handoff exits/reductions.

## Persistence

No Room migration is required.

Champion state uses the existing durable `research_state` table:

- `m9_champion:<symbol>`
- `m9_champion_promoted_at:<symbol>`
- `m9_champion_reason:<symbol>`

Promotions are audited as `m9_strategy_promotion` research events.

## Authority limits

M9 cannot:

- create BUY signals
- increase position size
- raise risk budgets
- bypass M4 execution-state controls
- bypass M5 net-EV
- bypass portfolio/risk controls
- promote from generic symbol outcomes
- block a risk-reducing protective exit

## Run

Copy this ZIP into repository root preserving paths, commit to `main`, then run:

**Actions → M9 Champion Challenger Strategy Governance → Run workflow**

The Action verifies M9 then M8 → M7 → M6 → M5 → M4 → M3.2 → M3 → canonical,
compiles Kotlin, runs tests, builds the APK, and pushes:

`milestone/m9-champion-challenger-<run>`
