#!/usr/bin/env python3
from pathlib import Path
import sys

def text(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else ""

def main():
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    ws = text(repo / "app/src/main/java/com/ksp/cryptobot/exchange/KrakenWebSocketV2MarketData.kt")
    exchange = text(repo / "app/src/main/java/com/ksp/cryptobot/exchange/ExchangeClientsV08.kt")
    controller = text(repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt")
    service = text(repo / "app/src/main/java/com/ksp/cryptobot/service/BotForegroundService.kt")

    checks = {
        "Kraken WS v2 primary endpoint": 'wss://ws.kraken.com/v2' in ws,
        "ticker v2 subscription": '"ticker"' in ws and '"event_trigger", "bbo"' in ws,
        "OHLC v2 subscription": '"ohlc"' in ws and '"interval"' in ws,
        "application ping": '.put("method", "ping")' in ws and "APP_PING_INTERVAL_MS = 30_000L" in ws,
        "protocol ping": ".pingInterval(20, TimeUnit.SECONDS)" in ws,
        "silent connection watchdog": "SILENCE_DEADLINE_MS = 8_000L" in ws and 'state = "SILENT"' in ws,
        "exponential reconnect backoff": "MAX_BACKOFF_MS = 60_000L" in ws and "1_000L shl exponent" in ws,
        "reconnect jitter": "Random.nextLong" in ws,
        "subscription replay": "replay = subscriptions.toList()" in ws and "replay.forEach" in ws,
        "reconnect clears market cache": "tickerCache.clear()" in ws and "candleCache.clear()" in ws,
        "exchange status health": '"status" -> handleStatus' in ws and 'systemStatus == "maintenance"' in ws,
        "heartbeat health": '"heartbeat" -> Unit' in ws,
        "active universe bounded": "MAX_ACTIVE_SYMBOLS = 32" in ws and "setActiveSymbols" in ws,
        "v2 BTC symbol format": 'if (base == "XBT") base = "BTC"' in ws,
        "ticker WS-first then REST fallback": "KrakenRealtimeMarketDataRegistry.freshTicker" in exchange and "api.kraken.com/0/public/Ticker" in exchange,
        "OHLC subscription plus REST history": "KrakenRealtimeMarketDataRegistry.ensureOhlc" in exchange and "api.kraken.com/0/public/OHLC" in exchange,
        "latest WS candle merged": "KrakenRealtimeMarketDataRegistry.mergeLatestCandle" in exchange,
        "auto-rotation active-set handoff": "KrakenRealtimeMarketDataRegistry.setActiveSymbols(selected)" in controller,
        "fallback active-set handoff": "KrakenRealtimeMarketDataRegistry.setActiveSymbols(fallback)" in controller,
        "M3 host starts feed": "configureRealtimeMarketData(startSettings" in service,
        "M3 host gates feed on validated network": "KrakenRealtimeMarketDataRegistry.onNetworkAvailable(network.usable)" in service,
        "M3 host respects setting": "settings.enableKrakenWebSocketFeed" in service,
        "M3 host Kraken-only": "settings.exchangeProvider == ExchangeProvider.KRAKEN" in service,
        "foreground notification surfaces WS state": "ws=${wsHealth.state}/${wsHealth.systemStatus}" in service,
        "host stop closes feed": "KrakenRealtimeMarketDataRegistry.stop()" in service,
        "no private WS order submission": "ws-auth.kraken.com" not in ws and "add_order" not in ws,
    }

    failed = []
    for name, ok in checks.items():
        print(("PASS" if ok else "FAIL") + " | " + name)
        if not ok:
            failed.append(name)
    if failed:
        raise SystemExit("M3.2 verification failed: " + ", ".join(failed))
    print("\nPASS | M3.2 Kraken WebSocket-first public market-data contracts satisfied.")

if __name__ == "__main__":
    main()
