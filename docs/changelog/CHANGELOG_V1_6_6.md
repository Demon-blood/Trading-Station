# v1.6.6 Paper Mode Fix

This release fixes paper trading behavior so PAPER mode works as a real local simulation instead of showing empty portfolio/status-off states.

## Fixed

- PAPER provider is now treated as executable simulation mode.
- Foreground service now passes execution=true for PAPER mode, so paper orders can be simulated.
- Live Status readiness now treats PAPER as a valid provider/mode instead of showing provider/execution as OFF.
- Added persistent local paper wallet using SharedPreferences.
- Paper wallet starts with simulated EUR 1000.00.
- Paper BUY orders subtract quote balance and add base asset.
- Paper SELL orders subtract base asset and add quote balance.
- Portfolio tab now shows Paper Portfolio and displays local simulated balances.
- Paper exchange capability now allows simulated trading while still never sending real exchange orders.

## How to use

1. Settings -> Provider: PAPER.
2. Bot -> Mode: PAPER.
3. Portfolio -> Refresh Paper.
4. Bot -> Start Service or Dashboard -> Execute.
5. Live Status should show paper=true and execute=true.
