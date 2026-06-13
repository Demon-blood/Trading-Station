# Crypto TradeStation v3.1.3 — News Main Tab Only + Multiple NewsAPI Keys

Changed:
- Removed AI Signals from the main top tab row.
- Kept News Dashboard as a main top tab.
- NewsAPI key field now accepts multiple keys separated by comma, semicolon, or new line.
- News client now creates one NewsAPI provider per saved key.
- News provider labels are stored as NewsAPI-1, NewsAPI-2, etc.
- Scan status now reports how many NewsAPI keys are active plus CryptoCompare.

Usage:
- Settings > Basic Settings > NewsAPI key(s), comma-separated
- Example: key1,key2,key3
