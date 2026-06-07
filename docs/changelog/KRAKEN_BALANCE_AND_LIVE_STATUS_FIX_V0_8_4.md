# v0.8.4 Kraken balance and live status fix

This build adds a real Kraken balance pre-check before live BUY orders. The bot now:

- Reads Kraken available EUR balance through `BalanceEx`, falling back to `Balance`.
- Logs available EUR in the Live Status tab.
- Reserves EUR per submitted order inside the same scan cycle.
- Reduces the second/next order size if the remaining EUR is below the configured max position.
- Blocks orders below a conservative 5 EUR minimum.
- Adds a 1% reserve for trading fees/slippage before submitting orders.

Why this was needed: with 46 EUR available and a max position of 25 EUR, the bot could submit one 25 EUR order and then try another 25 EUR order in the same scan. Kraken then correctly returned `EOrder:Insufficient funds` because the first open order locked part of the EUR balance.
