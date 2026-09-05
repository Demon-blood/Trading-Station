# Crypto TradeStation — M21 Chaos Testing, Recovery & Data Integrity

M21 is a production-safety milestone. It adds no alpha, does not increase size, and does not relax M12–M20 gates.

## Runtime hardening

- The pre-AddOrder durable `SharedPreferences.commit()` result is now mandatory. If unresolved intent cannot be committed, the network submission boundary fails closed.
- Ambiguous state must also be durably committed.
- A failed quarantine clear restores the in-memory row, keeping BUY fail-closed.
- Kraken private execution state starts with a recovery fence closed.
- Network loss, private disconnect, stream silence, stale/out-of-order sequence, sequence gap, and failed service reconciliation close the fence.
- Only a successful full `BotForegroundService.reconcileAfterRecovery()` may reopen BUY eligibility.
- Protective/exit SELL remains outside the BUY recovery fence.
- Private execution `exec_id` is filtered idempotently in-process; M13 remains the durable cross-process execution ledger.
- Recovery SELL quantity is always capped to authoritative base exposure.

## Chaos matrix

Unit tests cover:

- kill after AddOrder intent / before ACK;
- kill after fill / before DB write;
- Wi-Fi loss;
- WS + REST truth loss;
- duplicate private event;
- stale/out-of-order private event;
- sequence gap;
- reboot;
- DB unavailable;
- distributed lease unavailable;
- protective-stop ACK lost;
- cancel ACK lost;
- amend response lost;
- late fill after cancel;
- Doze-like network suspension;
- wall-clock rollback.

## Android runtime reality

Current Android guidance documents that Doze can suspend network access and restricted battery mode can prevent expected background execution on some devices. M21 therefore treats process/network gaps as unknown exchange state and requires authoritative reconciliation before new BUYs.

## Run

Copy the package into repository `main`, preserving paths, commit it, then run:

**Actions → M21 Chaos Recovery & Data Integrity → Run workflow → main**

Expected milestone branch:

`milestone/m21-chaos-recovery-<run-number>`
