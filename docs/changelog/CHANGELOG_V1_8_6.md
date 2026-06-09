# Crypto TradeStation v1.8.6 — Backtest + Forward Test Button Fix

Fixes:
- Run Sample Test button now executes a real local sample backtest simulation.
- Forward Test button now executes a real local forward-test simulation.
- Backtest tab now displays result cards after running each test.
- Shows trades, win rate, profit factor, net return, max drawdown, and live-gate status.
- Version label updated to v1.8.6 CTS.

Notes:
- This is an offline local simulation using generated candle data so the buttons respond immediately.
- It is designed to verify the UI flow and strategy-gate logic.
- Future upgrade can connect these buttons directly to Kraken historical OHLC data.
