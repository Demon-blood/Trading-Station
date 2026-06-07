# Project Structure

The source code is grouped by responsibility under:

```text
app/src/main/java/com/ksp/cryptobot/
```

## Main areas

```text
MainActivity.kt
```

Main Compose UI entry point. Contains app tabs, screen layout, and UI callbacks into the controller.

```text
core/
```

Application-level orchestration models and the main `BotController`.

```text
exchange/
```

Exchange adapters. Kraken is the primary live exchange. Paper trading is used for simulation.

```text
strategy/
```

Technical indicators, recommendation engine, market regime detection, position sizing, and multi-strategy logic.

```text
execution/
```

Risk guards and execution validation before any live order is allowed.

```text
order/
```

Smart order planning, stale order logic, market/limit order behavior, and order-related automation.

```text
lifecycle/
```

Trade lifecycle and position tracking logic.

```text
intelligence/
```

AI-style scoring, trade memory, news sentiment, and advanced memory logic.

```text
autonomous/
```

Self-optimization, auto-disable, paper/live learning, remote command parsing, and autonomous diagnostics.

```text
pro/
```

Pro automation layer: WebSocket-ready monitoring, profit-lock logic, symbol scoring, watchdog checks, and other advanced systems.

```text
data/
```

Room database entities, DAO, and database definition.

```text
settings/
security/
status/
```

Settings persistence, encrypted secret storage, and Live Status timeline storage.

```text
tax/
```

Belgian tax calculation/export helper logic.
```
