# UI Layout Fix v0.6

This update fixes the button-overlap issue seen in the v0.5 preview.

## Changes

- Added `PaddingValues(top = 16.dp, bottom = 112.dp)` to major `LazyColumn` screens.
- Converted cramped button rows into horizontal `LazyRow` action strips.
- Converted execution-mode chips into a horizontal `LazyRow` so long labels do not overflow.
- Kept critical actions inside cards instead of near the navigation area.
- Added enough bottom-safe padding so scrollable content does not sit underneath bottom tabs/navigation.

## Design rule

No critical button should be placed within the bottom navigation safe zone. Long button groups should be horizontally scrollable or placed in a dedicated command card.
