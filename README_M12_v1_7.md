# M12 v1.7 — consolidated applier + verifier hotfix

This supersedes v1.1 through v1.6 hotfixes.

No trading-strategy or risk-policy change is introduced by v1.7. It consolidates
the M12 delivery tooling fixes so replacing one file cannot reintroduce an older bug.

## Included fixes

1. Indentation-aware reconciliation insertion in both:
   - `reconcileLiveExecutionState(...)`
   - `scanOnce(...)`

2. Semantic distributed-authority gate insertion around the existing Kraken
   `canSubmitNewEntry(...)` gate.

3. Real newline when copying all M12 Kotlin/JUnit payload files.
   Literal backslash+n EOF is rejected immediately.

4. Real newline when extending `cloudshare_setup/schema.sql`.

5. Correct PAPER verifier contract:
   - PAPER branch exists
   - authorized `EngineAuthoritySnapshot(true, "PAPER", ...)`
   - PAPER snapshot is returned

## Revision proof

A correct new run must show:

`INFO | M12 applier revision v1.7`

and then:

`INFO | M12 verifier revision v1.7`

If either marker is absent, that workflow is not running both consolidated files.

## Replace BOTH files

- `tools/apply_m12_order_truth_authority.py`
- `tools/verify_m12_order_truth_authority.py`

Commit both replacements to `main`, then launch a NEW M12 workflow_dispatch from
`main`. Do not use Re-run jobs.
