# v1.7.0 True Self-Learning

Implemented complete bounded self-learning for the project:

- Added `TrueSelfLearningEngine`.
- Added Room entities for feature snapshots, learned symbol profiles, learned strategy profiles and learning audit rows.
- Added DAO queries/upserts for learned profiles.
- Added persistent settings for learning sample size, lookback, max score boost/penalty and learned sizing.
- Integrated learning refresh into the bot scan loop.
- Integrated learned decision adjustment into AI decisions.
- Added feature-snapshot recording for every scanned symbol decision.
- Added new Self Learning UI tab.
- Added Advanced Settings controls for learning parameters.

Learning remains bounded and explainable; it cannot override live trading safety gates.
