# Crypto TradeStation M11 — Execution Fail-Closed & Durable Unknown-State Hardening

M11 fixes the remaining high-priority LIVE execution truth gaps.

## 1. Startup reconciliation is now fail-closed

Before M11:

```text
reconcileAfterRecovery() == false
→ notification says "waiting"
→ controller.start() still executes
```

M11 changes startup to:

```text
reconcile
   ↓ fail
do NOT start controller
   ↓
retry while durable RUN intent remains active
   ↓
start controller only after authoritative reconciliation succeeds
```

## 2. Exchange API failure is no longer an empty snapshot

Before M11, `AdvancedExecutionCoordinator.reconcileLive()` did:

```kotlin
getPortfolioBalances() failure → emptyList()
getOpenOrders() failure        → emptyList()
```

That could make transport/API uncertainty look like:
- zero exchange balances
- zero open orders

and local positions could be marked zero.

M11 routes both through `ExecutionTruthGate.requireAuthoritative()`.

A legitimate provider response of an empty list remains valid.
An exception throws `ExchangeTruthUnavailableException` before local positions are read/mutated.

## 3. Strict startup/recovery reconciliation boundary

`BotController.reconcileLiveExecutionState()` now performs:

```text
exchange client
→ lifecycle pre-scan maintenance
→ AdvancedExecutionCoordinator.reconcileLive()
→ authoritative balances
→ authoritative open orders
→ local reconciliation
→ Kraken REST truth refresh
```

Foreground-service recovery must pass this boundary first.

The older open-order/lifecycle/portfolio snapshots remain useful diagnostics, but they no longer establish execution truth.

## 4. AddOrder ambiguity survives process death

M4 already quarantined ambiguous AddOrder results in memory, but process death erased them.

M11 adds `KrakenDurableExecutionQuarantine`.

Before sending Kraken AddOrder:

```text
clientOrderId
symbol
side
timestamp
status=PENDING
```

is synchronously committed to SharedPreferences.

Only then may the HTTP request cross the transport boundary.

Definitive acknowledgement/rejection or an authenticated execution report clears the durable record.

If the process dies while the result is unresolved:

```text
next process
→ reload durable unresolved AddOrder
→ restore it as AMBIGUOUS
→ Kraken entry gate blocks new BUYs
```

A service stop does not erase the durable unresolved quarantine.

## Authority model

M11 does not affect protective SELL/EXIT authority.

It only makes new LIVE entry authority stricter.

No Room migration is required; Room remains schema 12.

## Run

Copy this ZIP into repository root preserving paths, commit the bootstrap files to main, then run:

**Actions → M11 Execution Fail Closed Hardening → Run workflow**

Expected branch:

`milestone/m11-execution-fail-closed-<run>`
