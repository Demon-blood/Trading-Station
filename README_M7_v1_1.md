# M7 v1.1 — Canonical Room Verifier Hotfix

The first M7 run correctly upgraded AppDatabase from Room schema 11 to 12, but
the older canonical verifier was hard-coded to require exactly `version = 11`.

This hotfix makes the M7 Action update that canonical verifier safely:

- schema 11 remains valid before M7 is applied;
- schema 12+ is accepted only with an explicit `MIGRATION_11_12`;
- `MIGRATION_11_12` must be registered after `MIGRATION_10_11`;
- destructive migration remains prohibited by the existing canonical verifier;
- the corrected canonical verifier is committed into the M7 milestone branch,
  so merging M7 permanently fixes future milestone verification.

Apply these files over the repository root, commit to main, then rerun:
Actions -> M7 AI Value Attribution and Shadow Counterfactuals.
