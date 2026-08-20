# Confirmed integration/UX findings addressed by v3

## Navigation / settings

- Milestone 6 injected a user-visible `V4 Systems` top-level tab even though the app already had domain hubs.
- Diagnostics v2 then added `Settings Truth` inside that separate v4 UI, creating a second settings/diagnostics surface.
- The v4 container used a separate default Material3 card/tab presentation while the main app uses its own `GlassCard`/hub visual language, causing visible inconsistency.
- v3 removes the generic container from top navigation. Research is owned by AI; News provider health by News; CloudShare/Recovery by Settings/Backup; verification by System Test.

## Trading action contract / KAS-HBAR churn

- `RecommendationEngine` / base AI use `AVOID` and `STRONG_AVOID` as non-entry states.
- The lifecycle manager classified `SELL`, `AVOID`, and `STRONG_AVOID` together as bearish exit candidates. That was a semantic contract mismatch.
- The lifecycle layer did not receive same-scan information from the normal execution path and did not have a short-age guard for discretionary SELL signals.
- v3 makes only an explicit allowed `SELL` a discretionary signal exit and uses the existing `cooldownAfterBuyMinutes` as the soft-signal hold floor.
- Hard stop-loss, research/source protective EXIT/REDUCE, trailing/profit protection remain eligible immediately.
- v3 also passes same-scan entered/exited symbol sets to lifecycle so one scan cannot submit an entry/exit and then independently churn the same symbol through the second execution authority.

## Position truth / learning

- Stored entry/opening lifecycle rows could outlive a later re-entry. v3 prefers a newer confirmed BUY and resets opening/high-water truth for a reopened lifecycle.
- Stale OPEN position rows are reconciled only after a successful exchange portfolio read, so a temporary API failure cannot close all positions in the local DB.
- Milestone 4 already contains confirmed-fill lifecycle accounting hardening. v3 explicitly validates that effective code is present so the older submission-as-completed-trade behavior cannot return unnoticed.
- The earlier diagnostics fix continues to train symbol/strategy learning on completed SELL outcomes rather than BUY+SELL ledger-row counts.

## Wiring verification

- The Action now validates effective post-migration source, not only migration-overlay markers.
- A BotSettings round-trip persistence audit enumerates every constructor field, reports any field missing from load/save, and fails the build if a safety/execution-critical field is missing.
- Failure diagnostics now include `integration-cleanup.log` and `integration-contracts.log`.
