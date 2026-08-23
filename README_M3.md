# Crypto TradeStation M3 — Android 24/7 Runtime Host

This is GitHub-Actions-first.

Copy this ZIP into the repository root and commit the bootstrap files to `main`.
It does **not** modify `app/` until the workflow is run.

Then run:

**Actions → M3 Android 24-7 Runtime Host → Run workflow**

The workflow will:
1. apply the host patch to a clean canonical `app/`;
2. verify that only the manifest/service host files changed;
3. rerun the canonical v4.0.7 verifier;
4. compile Kotlin;
5. run unit tests;
6. assemble a debug APK;
7. push `milestone/m3-runtime-host-<run>`;
8. attempt to open a PR.

## M3 v1 scope

- `dataSync` → truthful `specialUse` foreground-service type;
- remove Android 15 six-hour `dataSync` design conflict;
- durable continuous-run intent;
- START_STICKY resumes only if the user still requested continuous running;
- user Stop clears auto-resume intent;
- user Background Auto start opts into reboot/package-update resume;
- BOOT_COMPLETED and MY_PACKAGE_REPLACED recovery;
- validated internet gating;
- Wi-Fi/cellular transition detection;
- new scans/orders pause when network is unvalidated;
- Kraken health + open orders + lifecycle + portfolio refresh before post-recovery resume;
- existing LIVE_AUTO scan keeps its current advanced reconciliation immediately before live execution;
- runtime state/heartbeat/failure persistence;
- persistent notification with Stop action;
- Android background, Doze, and notification health checks;
- no alarms/restart loops/exact-alarm workaround.

## Not changed in this slice

- strategies
- AI
- order sizing
- exchange order logic
- risk limits
- Room schema
- Kraken API credentials
- CloudShare
- research engines

## Android policy note

`specialUse` is an official Android foreground-service type for valid long-running foreground use cases that do not fit another category. Google Play reviews the free-form subtype explanation if the app is distributed through Play. Sideload/private distribution avoids Play review, but Android OS foreground-service/background rules still apply.
