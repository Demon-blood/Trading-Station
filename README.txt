Crypto TradeStation v4 - GitHub Actions KSP Fix 2

Replace this exact repository file:
.cts-v4-migration/app/src/main/java/com/ksp/cryptobot/data/CloudShareDao.kt

Cause fixed:
Room/KSP treats ACTION as a SQL keyword. The two CloudShare aggregate queries now quote
`action` as an identifier in SELECT/GROUP BY/ORDER BY while keeping the projection column name action.

After committing this file to main, the Crypto TradeStation v4 Build workflow will run automatically.
