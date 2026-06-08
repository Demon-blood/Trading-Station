# v1.7.7 EUR Cash Routing Fix

This build treats Belgian Kraken SEPA deposits as the primary spendable EUR cash bucket.

Kraken may report EUR funding balances as `ZEUR` internally while tradable pairs use `EUR` as the quote asset, for example `BTCEUR` or `ETHEUR`.

The bot now maps these aliases together:

- `EUR` <-> `ZEUR`
- `USD` <-> `ZUSD`
- `GBP` <-> `ZGBP`
- `BTC` <-> `XBT` / `XXBT`
- `ETH` <-> `XETH`

Recommended Belgian setup:

```text
Auto Symbol Universe: ALL
Allowed Quote Assets: EUR
Allow Non-EUR Quote Buys: optional
Crypto-to-Crypto Trading: OFF unless intentionally testing
```

This means the scanner may analyze all Kraken symbols, but live BUY routing prioritizes EUR cash from Belgian deposits. SELL routing remains available for assets you already hold.
