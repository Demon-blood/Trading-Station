# Crypto TradeStation v2.8.7 — Currency/Stablecoin Base Blocklist

Fixes:
- Prevents the bot from repeatedly buying cash-like base assets such as EURC and USDG.
- Auto symbol discovery now disables currency/stablecoin base pairs.
- Configured fallback symbols are also filtered.
- Execution has a final BUY guard that blocks cash/stablecoin base assets even if they slip through discovery.

Why:
- Kraken exposes fiat/stablecoin pairs such as EURC/EUR or USDG/EUR as tradable pairs.
- The scanner previously treated every tradable pair as a normal market target.
- Currency/stablecoin assets should usually be treated as quote/cash/reserve assets, not growth targets.

Blocked as BUY targets:
- EUR, USD, GBP, CHF, AUD, CAD, JPY
- EURC, EURT, EURI
- USDC, USDT, USDG, USDS, USDE
- DAI, PYUSD, TUSD, BUSD, GUSD

SELL remains allowed for held assets when balance exists.
