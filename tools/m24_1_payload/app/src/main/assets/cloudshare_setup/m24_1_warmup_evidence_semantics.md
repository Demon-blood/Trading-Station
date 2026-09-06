# M24.1 Warmup / Evidence Semantics

Crypto TradeStation must distinguish **data readiness** from **outcome-learning readiness**.

## Data readiness

Fresh `shared_signal_daily`, `shared_learning_daily`, and `shared_trade_daily` aggregates prove that CloudShare data is flowing. Their `sample_count` values are retained in the local collective index even when the rows are not realized outcomes.

Data readiness becomes `READY` only when:

- CloudShare collective learning is enabled;
- enough indexed evidence exists (bounded to at most 10 samples for this connectivity/data-readiness check); and
- the newest indexed evidence is no more than 24 hours old.

This state is diagnostic only. It does not create trade edge and does not increase a trading score.

## Outcome-learning readiness

Only rows explicitly classified as resolved outcomes can contribute to collective win rate, edge, and score adjustment. Android currently treats resolved `SELL` / `EXIT` / `CLOSE` trade aggregates as outcome evidence; observational decisions and signals remain non-outcomes.

The configured collective minimum (25 by default) remains the required threshold for the matching symbol/strategy evidence tier before collective score adjustment becomes active.

Therefore these states are both valid at the same time:

- `Data readiness: READY`
- `Outcome learning: COLLECTING_OUTCOMES (for example 3/25)`
- `Collective adjustment: 0 / neutral`
- `Local strategy: active`

## Upgrade repair

M24.1 introduces a new one-time collective reindex marker. Existing downloaded CloudShare intelligence is reindexed once after upgrade so signal/decision sample counts that were previously stored as zero are repaired without requiring the Worker to resend old events.

## Non-goals

M24.1 does not fabricate outcomes, lower the resolved-outcome safety threshold, alter Kraken execution authority, or infer strategy labels that are not present in the stored trade row.
