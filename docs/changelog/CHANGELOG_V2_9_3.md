# Crypto TradeStation v2.9.3 — Chart Active Symbols Compile Fix

Fixes:
- ChartScreen call passed activePositionSymbols, but the ChartScreen function signature did not declare that parameter.
- Added activePositionSymbols: List<String> to ChartScreen.
- This resolves the compile errors around activePositionSymbols and the downstream type inference errors in the chart symbol chips.
