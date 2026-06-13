# Crypto TradeStation v2.9.4 — Chart Main Parameter Fix

Fixes:
- AppTab.CHART had the new activePositionSymbols parameter.
- AppTab.CHART_MAIN still called ChartScreen without activePositionSymbols.
- Added activePositionSymbols = activeChartSymbols to the CHART_MAIN call.

Build error fixed:
- No value passed for parameter 'activePositionSymbols' at MainActivity.kt line 708.
