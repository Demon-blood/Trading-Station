# Module Map

## Trading flow

```text
BotForegroundService
→ BotController.scanOnce(...)
→ exchange client loads ticker/candles/balances
→ strategy engine creates AI decisions
→ autonomous/pro/risk engines adjust/block decisions
→ order manager builds order plan
→ exchange client submits order
→ status store logs every step
```

## Live exchange flow

```text
Settings
→ ExchangeProvider.KRAKEN
→ KrakenSpotClient
→ AssetPairs validation
→ Balance/OpenOrders/ClosedOrders sync
→ AddOrder/CancelOrder
```

## Paper trading flow

```text
Settings
→ ExchangeProvider.PAPER
→ PaperExchangeClient
→ simulated portfolio/order events
→ no real exchange orders
```

## UI flow

```text
MainActivity
→ AdvancedBotApp
→ selected AppTab
→ screen composable
→ callbacks into BotController
```

## Important status/debugging flow

```text
BotController / services
→ BotStatusStore.add(...)
→ Live Status tab
→ foreground notification summary
```

If something does not trade, check Live Status first.
