# Crypto TradeStation v2.8.1 — Auto-Tune Live Active Universe

Fixes:
- Strategy Auto-Tuner no longer tests only the first configured/default symbol.
- Auto-Tuner now tests every strategy across configured symbols plus active position symbols.
- Active-position results are sorted first and marked ACTIVE POSITION.
- Added Apply Best Passed Strategy To Live Settings.
- Applying writes the best passed strategy to live settings:
  - strategyMode
  - symbolsCsv active universe
  - enableBacktestGate
  - enableForwardTestGate
  - adaptiveStrategyLearningEnabled
  - autoTradeMultipleSymbolsPerScan
  - autoSymbolDiscoveryEnabled

Safety:
- Applying Auto-Tune does not bypass LIVE_AUTO preflight, release safety, balance/risk limits, max buy price, order-book guard, or stop-loss rules.
