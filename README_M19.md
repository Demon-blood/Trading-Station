# M19 — Self-Learning Model Governance & Drift Control

M19 requires the validated M18 app changes to already exist on `main`.

The M19 workflow deliberately runs the M18 verifier BEFORE applying M19. If the M18
pull request has not actually been merged, M19 stops immediately without touching app
source.

## Why M19 exists

The existing self-learning engine learned from realized PAPER/LIVE exits and persisted
symbol/strategy/hold profiles, but a learned score could still convert WAIT into BUY and
learned multipliers could reach 1.5x–1.6x.

M18 closed score-promotion paths in RecommendationEngine and AiDecisionEngine.
M19 closes the remaining online-learning promotion path.

## Monotonic learning authority

Online learning may reduce an existing decision. It may not create entry authority.

- WAIT -> WAIT/AVOID only
- WATCH -> WATCH/WAIT/AVOID only
- AVOID -> AVOID
- SMALL_BUY -> SMALL_BUY/WATCH/WAIT/AVOID, never BUY
- BUY -> BUY/SMALL_BUY/WATCH/WAIT/AVOID
- SELL -> SELL

LIVE learned sizing is capped at <= 1.00x of deterministic pre-learning capital.

A statistically successful learner can regain reductions back toward 1.00x; it does not
scale above the deterministic M5/M17/risk allocation. PAPER may explore only up to 1.10x.

## Drift and calibration

M19 evaluates:

- feature drift:
  - final score
  - spread
  - log-volume
  - absolute 24h price movement
- regime-distribution drift using total variation
- execution-model drift from realized slippage
- realized performance decay
- confidence/probability calibration using Brier score
- three-chunk chronological outcome stability
- M7 AI/model value attribution, including individual `modelPath` evidence

Insufficient evidence remains `unknown`; it is never rewritten as zero drift or perfect
calibration.

## Statistical positive-learning gate

Positive score adaptation requires all of the following:

- enough recent realized exits;
- recent net P&L > 0;
- lower 95% confidence bound of mean realized P&L > 0;
- no detected performance decay;
- stable chronological thirds;
- confidence calibration available and not severe;
- no severe execution drift;
- no defensively negative model-path attribution.

Positive score adjustment is still capped at +3 and remains unable to create entry
permission.

## Automatic rollback

M19 enters ROLLBACK on severe feature/regime/execution drift, statistically negative
recent performance, severe confidence miscalibration, unstable outcome chunks, or
credible negative AI/model-path attribution.

Rollback is defensive:

- positive score learning -> 0;
- LIVE learned size ceiling -> 0.60x;
- learned hold deferrals -> disabled;
- maker fill probability adjustment -> non-positive only;
- stale timing multiplier -> 0.75..1.00;
- amend fill-probability threshold -> 0.45..0.60.

No M19 component edits or weakens deterministic:
- daily loss limits;
- position caps;
- stop settings;
- DMS;
- engine lease/fencing;
- kill switch;
- API-key permissions.

## M18 truth interaction

Persisted learned strategy preferences are validated through `StrategyTruthRegistry`.

A historical learned profile cannot resurrect:
- fake grid;
- fake market making;
- fake funding/news;
- fake pairs relative strength;
- fake DCA;
- fake whale attribution;
or any future strategy whose M18 truth spec is not live-selectable.

## Execution-model learning

M15 remains the owner of actual fill-time/slippage calibration.

M16 remains the owner of L2 heuristic fill probability.

M19 governs those learned execution values:

- learned fill timing is additionally multiplied by a bounded 0.75..1.00 governance factor;
- M16 fill probability may only receive a 0..-0.08 defensive offset;
- amendment threshold is bounded 0.45..0.60;
- realized recent slippage becomes a bounded 0..25 bps safety-buffer diagnostic for M20.

M19 does not claim L2 gives exact queue probability.

## Persistence

No Room migration is introduced.

M19 reuses:
- `SelfLearningAuditEntity` for durable governance audit records;
- `production_intelligence_state` for a compact `m19_learning_governance` state;
- existing learned symbol/strategy/hold profiles.

Room stays version 12.

## Run

1. Ensure PR #18 is actually merged into `main`.
2. Commit the M19 bootstrap package contents to `main`.
3. Run:

Actions -> M19 Self-Learning Model Governance & Drift Control -> Run workflow -> main

Expected branch:

`milestone/m19-learning-governance-<run-number>`

The Action verifies M18 before M19 applies, then verifies M19 and every prior milestone,
compiles Kotlin, runs unit tests, builds the APK, creates the controlled milestone branch,
and opens the PR when GitHub permissions permit.
