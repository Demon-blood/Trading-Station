M25 V4 — PROVEN GITHUB ACTIONS PATTERN

This replaces the V2/V3 M25 bootstrap logic.

WHAT CHANGED
------------
- No PAT / CTS_AUTOMATION_TOKEN.
- No local BAT/PowerShell/Python step.
- M25 no longer edits android-canonical-build.yml in the generated milestone branch.
- M25 verifies the actual AppDatabase Room schema 12 instead.
- Generated milestone commit is APP-ONLY, identical to the pattern that worked for
  M23, M24 and M24.1.

ONE-TIME BOOTSTRAP
------------------
Place the contents of this ZIP at repository root on main and commit them using the
same GitHub bootstrap method used for prior milestones.

After that, everything happens in GitHub Actions.

RUN
---
GitHub -> Actions -> M25 Final RC Burn-In Controlled LIVE -> Run workflow -> main

EXPECTED RESULT
---------------
The next run number will create:
  milestone/m25-final-rc-<run_number>

Its implementation commit must contain exactly four app files and zero workflow files.

IMPORTANT
---------
The existing stale roomSchema=11 text in canonical INSTALL_IDENTITY output is treated
as metadata cleanup only. It is NOT used as evidence of the real database schema.

M25 uses:
  AppDatabase @Database version = 12
  MIGRATION_11_12
as source truth.
