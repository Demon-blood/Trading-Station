# M10 v1.2 — Short-span regression test correction

The runtime engine is unchanged.

The failing test `oneBadDayCannotStatisticallyDisable` used 20 synthetic outcomes
at -1.0% each with a configured 10% maximum drawdown. That creates roughly 20%
rolling drawdown, so the independent hard-drawdown safety gate correctly returns
LIVE_DISABLED even though the statistical evidence span is only one day.

The corrected test uses -0.3% per outcome:
- 20 samples
- one-day evidence span
- negative P/L and mean return
- profit factor below 0.90
- approximately 6% rolling normalized drawdown (< 10% hard limit)

That isolates the intended three-day statistical-disable rule and correctly expects
PROBATION.

Replace:
tools/m10_payload/app/src/test/java/com/ksp/cryptobot/research/ChampionDegradationEngineTest.kt

Commit to main and rerun the M10 Action.
