# Live Trading Checklist

Before using real funds:

## Kraken account

- [ ] API key created in Kraken Pro.
- [ ] Query funds enabled.
- [ ] Query open/closed orders enabled.
- [ ] Create/modify orders enabled.
- [ ] Cancel/close orders enabled.
- [ ] Withdrawal permission disabled.
- [ ] Free EUR exists for buy orders.
- [ ] Free crypto exists for sell orders.

## App settings

- [ ] Exchange Provider is `KRAKEN`.
- [ ] Bot Mode is `LIVE_AUTO`.
- [ ] Manual Execution Mode is OFF.
- [ ] Live Trading Acknowledgement is ON.
- [ ] Max Position EUR is small for first test.
- [ ] Symbols are valid Kraken spot pairs.
- [ ] Market orders are OFF until limit orders are confirmed.

## First live test

Use:

```text
Symbols: BTCEUR or ETHEUR only
Max Position EUR: 5
Market Orders: OFF
```

Confirm in Live Status:

```text
pair valid
free balance detected
risk guard passed
order submitted or skipped with clear reason
```
