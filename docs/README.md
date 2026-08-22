# Crypto TradeStation v4.0.7 Stabilization Patch — Diagnostic Repair Revision

Target repository: `Demon-blood/Trading-Station`.

This revision extends the v4.0.7 stabilization work after review of the 2026-08-22 full diagnostics. It is designed to run **after** the existing v4 generators/UI/diagnostics steps so the generated APK source receives the fixes rather than only the base source tree.

## Execution-integrity fixes

- Fixes the canonical workflow identity regression that declared 4.0.7/112 but later rebuilt/verified the APK as 4.0.6/111.
- Stops `BotController` from converting `executedQuantity=0` into a fabricated full trade record for accepted/resting orders.
- Treats Max Position as a **total exposure cap**, subtracting held exposure and already-open BUY notional before a new order is sized.
- Re-enforces Max Position inside the PAPER simulator at **actual fill time**.
- Uses one process-wide PAPER order mutex so separate `PaperExchangeClient` instances cannot concurrently mutate the same persisted wallet/order state.
- Rejects reused PAPER `clientOrderId` values.
- Makes deferred PAPER fill IDs deterministic from source order + cumulative fill progress so exact replay is detectable without collapsing legitimate partial fills.
- Makes execution-critical PAPER wallet/pending-order persistence synchronous.
- Keeps per-symbol learning warm-up details in telemetry without making the whole bot look permanently stuck in warmup.

## One-time legacy PAPER repair

The latest diagnostics contain two identical deferred PAXG fills for the same source order only 4 ms apart. The new one-time repair is deliberately narrow:

- PAPER rows only; LIVE/Kraken history is never deleted.
- A row is considered a legacy duplicate only when source order, symbol, side, quantity, price and fee are identical **and** the repeated legacy deferred-fill timestamps are within 2 seconds.
- The repair establishes a crash-resumable checkpoint before changing state.
- It removes only the duplicate PAPER trade rows, then rebuilds the PAPER wallet and cost basis from the surviving PAPER journal starting from the simulator's explicit €1000 baseline.
- Persisted PAPER position quantities are reconciled to the rebuilt wallet.
- Repair status is persisted under `paper_repair_v407` and exported in diagnostics under `[PAPER_REPAIR]`.
- System verification now performs a real `Position Exposure Invariant` check instead of merely saying the guard is enabled.

For the supplied diagnostic pattern, the expected repaired PAXG quantity is `0.02861780` (the original `0.01430861` plus one legitimate deferred `0.01430919`). The actual marked EUR value after repair depends on market price at runtime.

## Operational health / kill-switch classification

News provider quota/rate-limit failures are separated from execution-critical failures:

- Recognized provider quota/rate-limit errors from GDELT, GNews, Guardian, Marketaux, NewsAPI, NewsData.io, CryptoPanic and RSS do not add to the execution kill score.
- Real `order_error`, protective-exit failure, execution-integrity failure and database failure events remain critical and cannot be suppressed by provider-name matching.
- `order_error` contributes 5 points; the existing HIGH threshold of 5 therefore still trips on a single genuine order failure.
- Database/execution-integrity failures contribute 20 points.
- Production diagnostics report weighted score, critical-event count and ignored provider-quota noise.
- Unit tests cover both quota-noise suppression and genuine order failures.

## Database growth diagnostics

Full diagnostics now add `[DATABASE_TABLES]` with:

- database page size/count and reclaimable free-page estimate;
- row count for every non-system table;
- approximate per-table bytes when SQLite `dbstat` is available (otherwise `UNAVAILABLE`, never guessed);
- oldest/newest timestamp where the table has a known timestamp column;
- retention classification: `PERMANENT_LEDGER`, `STATE_KEEP_CURRENT_HISTORY_BOUNDED`, `ROLLING_TELEMETRY_CANDIDATE`, or `REVIEW`.

This revision intentionally **does not blindly delete database history**. The diagnostic first identifies the actual growth source so a later retention/compaction rule can be based on measured table usage rather than assumptions.

## Portfolio P/L truth fix

- `24H Realized P/L` means realized P/L from trades closed in the last 24 hours; it is no longer presented as total portfolio change.
- `All-Time P/L` means current marked portfolio value minus the explicit PAPER €1000 starting baseline.
- LIVE providers show `N/A — baseline not recorded` until a real live performance baseline is explicitly tracked; the app does not invent one.

## Install

From Windows PowerShell, after extracting this pack:

```powershell
powershell -ExecutionPolicy Bypass -File ".\cts_v4_0_7_stabilization\INSTALL_V4_0_7_STABILIZATION.ps1" "C:\path\to\Trading-Station"
```

Or run the Python installer directly:

```powershell
python .\cts_v4_0_7_stabilization\INSTALL_V4_0_7_STABILIZATION.py "C:\path\to\Trading-Station"
```

Then commit/push the resulting repository changes. The canonical GitHub Actions workflow applies the patch after the v4 source generators and runs the added static integrity contracts, Kotlin compilation, unit tests and APK build.

## Expected post-install diagnostic evidence

After installing/building v4.0.7 and opening PAPER portfolio/system diagnostics, look for:

```text
[PAPER_REPAIR]
... removed=1 ...

[DATABASE_TABLES]
SUMMARY|...
table=...|rows=...|bytesApprox=...|...|retention=...
```

The system verification should also contain `PAPER Legacy Duplicate Repair` and `Position Exposure Invariant`.

## GitHub access limitation during preparation

The connected GitHub integration could read the repository, but GitHub returned HTTP 403 when branch creation was attempted. This pack therefore does not claim to have been pushed or compiled by GitHub Actions from this session; the installer makes the repository changes locally and the canonical CI build validates them after you push.
