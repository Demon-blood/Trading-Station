# v1.0.0 Full Live Automation

This build upgrades the Kraken live bot from order placement into a complete lifecycle-managed trading system.

## Added

- Live trade lifecycle manager
- Positions tab
- Live position reconstruction from Kraken portfolio balances
- Kraken closed-order sync
- Local trade-history import from closed Kraken orders
- Automatic SELL management for held crypto
- Automatic take-profit exits
- Automatic stop-loss exits
- Trailing profit capture / profit maximizer
- Bearish-AI automatic sell exits
- Live performance summary
- Tax report row storage for lifecycle-managed exits
- Kraken STOP_LOSS and TAKE_PROFIT order-type support in the exchange layer
- v1.0 visible version label

## Important

No trading bot can guarantee maximum possible profits. This build attempts to maximize captured profit by combining:

- AI SELL decisions
- take-profit thresholds
- stop-loss protection
- trailing exits after profit activation
- Kraken balance and open-order checks
- live risk guards

Market orders remain optional and controlled by the existing market-order toggle.
