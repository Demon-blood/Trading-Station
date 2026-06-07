# v0.8.7 Kraken balance clarity + holdings-aware execution

This build explains the difference between free EUR cash and crypto portfolio value.

If Kraken reports `freeAvailable=0.04`, the bot has only €0.04 cash available for BUY orders. If the account shows around €46 total, that value is probably held as crypto such as ETH, BTC, or another asset.

Changes:

- Live Status now shows a tradeable balance snapshot for all positive free balances.
- Kraken diagnostics now shows all positive BalanceEx assets, not only EUR.
- BUY blocks now explain whether the user has crypto value but no free EUR.
- SELL order sizing now checks free base-asset balance before submitting to Kraken.
- Bot version label updated to v0.8.7.

The bot cannot buy crypto without free EUR cash. With crypto holdings but no EUR, it can only sell when a SELL signal is produced.
