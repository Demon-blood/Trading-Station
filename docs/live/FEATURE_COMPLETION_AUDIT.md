# v1.5.0 Live Completion Audit

This file exists so the project is honest about what is actually live, what is partial, and what cannot be implemented safely or truthfully.

## Live working target
The v1.5.0 project is a Kraken-focused Android live trading app. The only exchange connector intended for live order placement is Kraken Spot.

## Implemented live Kraken scope

| Area | Status | Notes |
|---|---:|---|
| Kraken API key/private key storage | Live | Stored through the app settings/secure settings layer. |
| Kraken public ticker data | Live | Used for price/spread checks and symbol scoring. |
| Kraken OHLC/candle data | Live | Used by strategy analysis. |
| Kraken AssetPairs discovery | Live | Used for EUR-pair discovery and pair metadata. |
| Kraken balance reading | Live | Used for free EUR and crypto availability checks. |
| Kraken portfolio reading | Live | Portfolio tab reads exchange balances. |
| Kraken open orders | Live | Orders tab and live status use open order data. |
| Kraken cancel order | Live | Manual cancel button and stale-order logic call cancel. |
| Kraken limit buy/sell | Live | Primary safe order path. |
| Kraken market buy/sell | Live, guarded | Requires explicit market-order toggle and risk/spread guards. |
| Automatic buy decision | Live | Runs strategy/risk/balance checks before order. |
| Automatic sell decision | Live | Uses bearish signals, balance checks, and exit management. |
| Auto symbol discovery | Live | Discovers and scores Kraken EUR pairs. |
| Auto rotation | Live | Uses discovered symbols when enabled, with fallback to configured list. |
| Live status diagnostics | Live | Explains scan, skip, validation, and order attempts. |
| Paper mode | Live | Simulated exchange mode; no Kraken order submission. |
| Manual execution mode | Live | Produces plans without placing orders. |

## Advanced features implemented as app logic but still require real-account validation

| Feature | Status | Why not marked fully proven |
|---|---:|---|
| Position lifecycle manager | Partial-live | Needs repeated real fill/partial-fill testing across several symbols. |
| TP/SL automation | Partial-live | Kraken order types exist, but OCO-style edge cases must be tested live. |
| Trailing profit lock | Partial-live | Strategy/explanation logic exists; live exit timing needs long-run validation. |
| Closed-order sync | Partial-live | Needs validation against your Kraken account history. |
| Trade-history/tax rows | Partial-live | Good for records, not accountant-certified tax output. |
| Strategy optimizer | Partial/scaffold | Present as logic/scaffold; not a complete optimizer with full parameter backtest loop. |
| WebSocket monitor | Partial/scaffold | WebSocket-ready structure exists; REST scan loop remains the primary live path. |
| Shadow paper/live comparison | Partial/scaffold | Records comparison notes; not a full statistical experiment engine. |
| Local explainable AI | Partial/scoring | Rule/scoring-based AI, not a trained ML model. |
| News intelligence | Partial | Requires provider/API key and validation. |
| Remote command parser | Scaffold | Local command parser exists; Telegram/Discord transport is not fully wired live. |
| Belgian tax export | Partial | CSV helper exists; tax correctness must be reviewed by a Belgian tax professional. |

## Not implemented as live features

| Feature | Status | Reason |
|---|---:|---|
| Binance live trading in Belgium | Not implemented | Binance spot trading was not available to the user under Belgian restrictions. |
| Bypassing Binance/Belgian restrictions | Refused | Illegal/terms-violating bypass logic is not included. |
| Coinbase Advanced live trading | Not live | Connector not fully signed/tested in this Android build. |
| Bitvavo live trading | Not live | Connector not fully signed/tested in this Android build. |
| Guaranteed/failproof trading | Impossible | No trading system can guarantee profit or avoid all losses. |
| Maximum possible profit selling | Impossible | The bot cannot know the future market top. It can only use trailing/TP/SL/bearish-exit rules. |

## Definition of “fully working live” used in this project
A feature is only treated as fully working live if it:

1. Has non-placeholder code.
2. Connects to a real provider or live exchange endpoint where relevant.
3. Logs what it is doing in Live Status.
4. Has explicit safety checks.
5. Can be tested by the user on a small live account.

Anything that does not meet these criteria is documented as partial or scaffold.
