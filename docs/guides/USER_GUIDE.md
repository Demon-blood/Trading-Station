# User Guide

## 1. Choose a mode

### Paper mode

Use paper mode when testing strategy behavior.

```text
Exchange Provider: PAPER
Bot Mode: PAPER
```

No API key is required.

### Live Kraken mode

Use live mode only after paper mode behaves correctly.

```text
Exchange Provider: KRAKEN
Bot Mode: LIVE_AUTO
Manual Execution Mode: OFF
Live Trading Acknowledgement: ON
```

## 2. Configure symbols

Safe starter list:

```text
BTCEUR,ETHEUR
```

Expanded list after validation:

```text
BTCEUR,ETHEUR,SOLEUR,XRPEUR,ADAEUR
```

The app should validate Kraken pairs before attempting live orders.

## 3. Configure risk

Start small:

```text
Max Position EUR: 5-10
Max Daily Trades: low
Market Orders: OFF for first tests
```

Enable market orders only after limit order behavior is confirmed.

## 4. Start the bot

Open the Bot tab and press Start Service.

Then open Live Status. The status timeline should show each step:

```text
service cycle started
settings loaded
exchange selected
balances loaded
symbol validated
decision created
risk checked
order submitted or skipped with reason
```

## 5. Understand skipped trades

A skipped trade is not always a bug. It can mean:

- no valid AI signal,
- score below threshold,
- no free EUR for buys,
- no free crypto for sells,
- spread too wide,
- market regime blocked,
- daily/weekly risk guard active,
- symbol invalid on Kraken,
- API permission missing.

Live Status should show the exact reason.
