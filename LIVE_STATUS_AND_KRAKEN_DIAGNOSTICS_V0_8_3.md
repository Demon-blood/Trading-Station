# v0.8.3 Live Status + Kraken Diagnostics

This build adds a live bot-status timeline so you can see exactly why the bot is or is not placing Kraken orders.

## New UI

- New **Live Status** tab
- Current bot step
- Readiness checklist
- Newest-first status timeline
- Kraken/order errors shown directly in the app
- Foreground notification now updates with the latest status

## What the timeline shows

- Service start/stop events
- Active provider, bot mode, manual-mode state
- Every scan cycle
- Ticker fetch result
- Candle fetch result
- AI decision, score and allowed flag
- Execution-guard block reason
- Kraken order submission attempt
- Kraken API error response, if any
- Successful live order ID

## Common reasons no Kraken trade is placed

- Exchange Provider is not `KRAKEN`
- Mode is not `LIVE_AUTO`
- Live trading acknowledgement is off
- Manual execution mode is on
- API credentials are missing or saved under the wrong provider
- Decision is `WAIT`, `WATCH`, or `AVOID`
- AI confidence is below the execution threshold
- Spread/volume/risk guard blocks the trade
- Kraken rejects the order due to key permissions, minimum order size, invalid pair, or insufficient funds

Kraken AddOrder uses `/0/private/AddOrder` and requires an API key with `Orders and trades - Create & modify orders`.
