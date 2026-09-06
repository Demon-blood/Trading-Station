M25 FINAL RC / BURN-IN / CONTROLLED LIVE - REPO ROOT PACKAGE

BASELINE
  main merge SHA expected before bootstrap:
  490d44ff4aafa2b97496774882e5bf91a2020d58

UPLOAD
  Extract this ZIP into the repository root on main.
  Commit the bootstrap files.

IMPLEMENTATION
  GitHub Actions ->
  "M25 Final RC Burn-In Controlled LIVE" ->
  Run workflow from main.

The implementation workflow:
  - requires merged M24.1;
  - applies the controlled M25 diff;
  - fixes canonical roomSchema identity 11 -> 12;
  - runs M24.1 -> M3 + canonical verifiers;
  - compiles, unit-tests, and builds a debug APK;
  - creates milestone/m25-final-rc-<run>;
  - attempts to open the M25 PR.

AFTER MERGE
  1. Wait for normal Canonical Android APK + OSV post-merge checks to go green.
  2. Complete real PAPER and shadow burn-in.
  3. Run "M25 RC Validation" in PRELIVE mode with the production CloudShare URL.
     It requires release signing secrets and does a read-only production health probe.
  4. Only after PRELIVE returns CONTROLLED_LIVE_ELIGIBLE should the operator perform
     the tiny LIVE validation in the app.
  5. Run "M25 RC Validation" again in POSTLIVE mode with the lifecycle/reconciliation
     attestations to produce RELEASE_READY evidence.

IMPORTANT
  No M25 GitHub workflow submits a Kraken order.
  External attestations are recorded explicitly and are not represented as machine-observed facts.
