# M25 Final Release Candidate / Burn-In / Controlled LIVE

M25 is an evidence gate, not an automatic trading milestone.

## Non-negotiable safety rule

CI and GitHub Actions MUST NOT submit a Kraken order. The workflow can build, test,
probe CloudShare read-only, verify signing, and evaluate operator evidence. A tiny
LIVE order is an explicit operator action performed in the app only after the
pre-LIVE gate reports `CONTROLLED_LIVE_ELIGIBLE`.

## Source-of-truth rule

M25 verifies the real Room source schema from `AppDatabase` and its migrations.
A stale informational `INSTALL_IDENTITY.txt` metadata string is not allowed to block
or falsify the code release-candidate state. Install identity metadata can be cleaned
up separately without changing database behavior.

## Readiness stages

### BLOCKED
Code/internal evidence is missing or any explicit M25 gate failed.

### CODE_RC
The source compiles, tests, passes the historical milestone verifier chain, verifies
Room schema 12 from source, preserves restart/network/partial-fill safety contracts,
and produces a canonical debug APK.

### CONTROLLED_LIVE_ELIGIBLE
Requires all of the following in addition to CODE_RC:

- signed release APK built with the configured release key;
- install/upgrade behavior operator-verified;
- production CloudShare `/v1/health` read-only probe passes:
  - `ok=true`
  - protocol `2026-07-26`
  - engine lease schema `2`
  - D1 query succeeds;
- Kraken API-key permission assessment is current and safe;
- distributed authority/fencing behavior has been verified;
- at least 24 hours PAPER burn-in;
- at least 24 hours shadow burn-in.

This stage permits a deliberately tiny operator-controlled LIVE test. It does not
mean the app is generally LIVE-ready.

### RELEASE_READY
After CONTROLLED_LIVE_ELIGIBLE, M25 additionally requires operator evidence for:

- tiny LIVE lifecycle completed;
- protective/exit SELL behavior verified;
- network-failure lifecycle verified without duplicate new-entry submission;
- fees and realized PnL reconciled against Kraken truth;
- final diagnostics bundle exported.

The partial-fill path is verified by deterministic unit/chaos coverage and execution
state handling. Do not intentionally manufacture a market partial fill merely to
satisfy a checklist.

## Existing safety contracts M25 preserves

- Unknown/stale Kraken execution truth blocks a new BUY.
- Ambiguous AddOrder intent is durably quarantined across process restart.
- `partially_filled` is an open-order state with cumulative quantity tracked.
- Network loss clears private execution readiness and requires authoritative recovery.
- M22 API-key security blocks new BUY when permissions are unsafe, missing, stale, or unknown.
- M24 distributed authority requires a fresh server-side fence before a LIVE new-entry BUY.
- Protective/exit SELL remains available for risk reduction.

## Hosted CloudShare truth

Repository source proves what the Worker is supposed to implement. It does not prove
that the production Worker/D1 deployment has been updated. M25 therefore performs a
real read-only `/v1/health` probe against the production URL supplied at validation.

No production deployment is claimed until that probe passes on the hosted endpoint.

## Tiny LIVE size

Use the smallest Kraken-valid order that is meaningful for the selected market and
within the operator's explicit loss budget. M25 intentionally does not hard-code a
EUR amount because exchange minimums and pair constraints can change.

Profit is not an M25 pass condition. Correct lifecycle, costs, authority, recovery,
and reconciliation are the pass conditions.
