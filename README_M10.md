# Crypto TradeStation M10 — Champion Degradation, Probation & Rollback

M10 completes the negative side of M9 champion/challenger governance.

M9 proves a strategy before granting LIVE research authority. M10 watches what happens **after promotion** and can defensively restrict or remove that authority when the champion deteriorates.

## State machine

```text
HEALTHY
  ↓
WATCH       (research-driven LIVE size ×0.75)
  ↓
PROBATION   (research-driven LIVE size ×0.50)
  ↓
LIVE_DISABLED
  ↓
ROLLBACK to a previously proven champion, if one still qualifies
```

Protective EXIT/REDUCE logic is outside this gate.

## Evidence

M10 does not use the champion's lifetime average. It uses exact strategy-tagged outcomes **after `m9_champion_promoted_at:<symbol>`**, retaining the latest 30 observations.

The same conservative M9 outcome representation is reused, including the extra closing-fee reserve used as an entry-fee proxy.

## Gates

WATCH can begin after 8 post-promotion outcomes when the rolling mean is negative, PF is below 1.0, or normalized drawdown reaches half the configured maximum.

PROBATION requires at least 12 observations and sustained negative economics / elevated drawdown. It keeps LIVE research entries possible, but caps M10-controlled research size at 50%.

LIVE_DISABLED requires either:

- a strategy-specific configured drawdown breach after at least 12 outcomes, or
- at least 20 outcomes spanning 3+ days, total rolling P/L <= -0.25 quote, mean return <= -5 bps/outcome, and an upper 95% return bound below zero.

A LIVE_DISABLED state does not automatically self-reenable.

## Rollback

M10 searches prior `m9_strategy_promotion` events. A previous champion is eligible only if it still passes conservative M9-style standalone requirements:

- 30+ exact outcomes
- 20+ PAPER outcomes
- 10+ chronological OOS outcomes
- 7+ days evidence
- 2+ regimes
- positive OOS net P/L
- >=5 bps OOS mean
- lower 95% OOS return > 0
- PF >= 1.20
- drawdown within configured maximum
- walk-forward PASS / score >=60
- Monte Carlo score >=60 / P(positive) >=65%

If no safe previous champion exists, research-driven LIVE entries remain disabled. M10 does not guess.

Rollback never authorizes an entry during the rollback scan. The next scan must evaluate the restored champion normally.

## Persistence

No Room schema migration is required. Existing `research_state` is used:

- `m10_health:<symbol>`
- `m10_health_reason:<symbol>`
- `m10_live_size_multiplier:<symbol>`

Audit events:

- `m10_champion_health_transition`
- `m10_champion_rollback`

## Authority limits

M10 cannot:

- create a BUY
- increase position size
- bypass M9
- bypass M5 net EV
- bypass M4 execution-state safety
- weaken risk limits
- block protective exits/reductions
- auto-reenable a disabled champion
- roll back to an unproven strategy

## Run

Copy this package into repository root, preserving paths, commit the bootstrap files to `main`, then run:

**Actions → M10 Champion Degradation Rollback → Run workflow**

The workflow verifies M10 then M9 → M8 → M7 → M6 → M5 → M4 → M3.2 → M3 → canonical, compiles Kotlin, runs unit tests, builds the APK, and pushes:

`milestone/m10-champion-degradation-<run>`
