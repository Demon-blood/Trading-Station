# v1.7.4 Self-Learning Tab Navigation Fix

This build fixes the UI navigation list so the existing `Self Learning` screen is physically reachable from the horizontal tab row.

## Fixed

- Added `Self Learning` to the visible top tab row.
- Added `Autonomous`, `Pro Systems`, `Backtest`, `Regime`, `News`, and `History` back to the visible tab row so advanced modules are reachable.
- Kept the existing `TrueSelfLearningEngine` logic intact.

## What the tab shows

- Learned symbol profiles
- Learned strategy profiles
- Learning controls/status
- Recent learning audit events
- Refresh Learning button

The self-learning engine was present in v1.7.x, but the tab was not included in the visible navigation list.
