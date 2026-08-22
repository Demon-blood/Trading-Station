# Crypto TradeStation v4.0.7 Stabilization Patch

Target repository: `Demon-blood/Trading-Station` at/after commit `1fe53b465074026afb3fc2054011f8b56098ad8c`.

## Fixes included

- Fixes the canonical workflow identity regression that declared 4.0.7/112 but later rebuilt/verified the APK as 4.0.6/111.
- Stops `BotController` from converting `executedQuantity=0` into a fabricated full trade record for accepted/resting orders.
- Treats Max Position as a **total exposure cap**, subtracting held base exposure and already-open BUY notional before sizing a new order.
- Re-enforces Max Position inside the PAPER simulator at **actual fill time**.
- Uses one process-wide PAPER order mutex so multiple `PaperExchangeClient` instances cannot concurrently mutate the same persisted paper wallet/order state.
- Rejects reused PAPER `clientOrderId` values.
- Makes deferred PAPER fill IDs deterministic from source order + cumulative fill progress, preserving legitimate partial fills while making exact replay detectable.
- Makes PAPER wallet and pending-order persistence synchronous for execution-critical state.
- Keeps per-symbol learning warm-up details in logs without allowing warm-up telemetry to become the long-lived top-level engine headline.
- Adds pure unit tests for deferred-fill identity and hard exposure-cap mathematics.

## Install

From the root of a local `Trading-Station` checkout:

```powershell
python path\to\cts_v4_0_7_stabilization\INSTALL_V4_0_7_STABILIZATION.py .
```

Then commit/push the resulting changes. The canonical GitHub Actions workflow will apply the patch after the v4 source generators and run the added integrity contract checks plus the existing Kotlin/unit/APK build verification.

## Important

The ChatGPT GitHub integration available during preparation had read access to `Demon-blood/Trading-Station` but GitHub returned HTTP 403 when asked to create a branch, so this package could not be pushed automatically from the session.
