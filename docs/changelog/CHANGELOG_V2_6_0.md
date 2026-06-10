# Crypto TradeStation v2.6.0 — Professional Ultimate Architecture

Implemented from the provided ultimate-bot design:
- OrderBookLevel and OrderBookSnapshot models.
- Exchange interface support for live order book snapshots.
- Kraken Depth endpoint implementation.
- Order book depth / slippage guard in the live execution path.
- Advanced Settings controls for order book execution quality.
- System Test rows for Order Book Guard and Professional Engine Layout.
- Backup/export fields for the new execution-quality settings.
- Architecture document mapping the app to Market Data, Strategy, Signal Scoring, Risk, Execution, Portfolio, Learning, Backtesting, Monitoring and Security layers.

Live behavior:
- In LIVE_AUTO, BUY can be blocked if Kraken order book depth is unavailable, too thin, or estimated slippage exceeds the configured limit.
- SELL/exit management remains available.
- The guard improves execution quality and avoids trades where fees/spread/slippage can destroy edge.

Safety:
- No profit is guaranteed.
- AI and scoring do not override hard risk/execution gates.
