#!/usr/bin/env python3
from __future__ import annotations

import os
import sys
from pathlib import Path

NEW_FILE = "app/src/main/java/com/ksp/cryptobot/exchange/KrakenWebSocketV2MarketData.kt"

def fail(msg: str):
    raise SystemExit("ERROR | " + msg)

def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        fail(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)

def main():
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    if not (repo / ".git").exists():
        fail("Not a git checkout")

    dirty = os.popen(f'cd "{repo}" && git status --porcelain -- app').read().strip()
    if dirty:
        fail("Refusing to patch dirty app/ tree:\n" + dirty)

    payload = Path(__file__).resolve().parent / "m32_payload" / NEW_FILE
    target = repo / NEW_FILE
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(payload.read_text(encoding="utf-8").rstrip() + "\n", encoding="utf-8")
    print("WRITE |", NEW_FILE)

    exchange_path = repo / "app/src/main/java/com/ksp/cryptobot/exchange/ExchangeClientsV08.kt"
    exchange = exchange_path.read_text(encoding="utf-8")

    ticker_anchor = '''        val rule = resolvePairRule(symbol)
        if (!rule.tradable) error("Kraken pair not tradable: ${rule.canonicalSymbol}. ${rule.status}")
        val req = Request.Builder()
            .url("https://api.kraken.com/0/public/Ticker?pair=${rule.exchangePair}")'''
    ticker_new = '''        val rule = resolvePairRule(symbol)
        if (!rule.tradable) error("Kraken pair not tradable: ${rule.canonicalSymbol}. ${rule.status}")
        KrakenRealtimeMarketDataRegistry.ensureTicker(rule.canonicalSymbol)
        KrakenRealtimeMarketDataRegistry.freshTicker(rule.canonicalSymbol)?.let { return@withContext it }
        val req = Request.Builder()
            .url("https://api.kraken.com/0/public/Ticker?pair=${rule.exchangePair}")'''
    exchange = replace_once(exchange, ticker_anchor, ticker_new, "Kraken ticker WS-first hook")

    candle_anchor = '''        val rule = resolvePairRule(symbol)
        if (!rule.tradable) error("Kraken pair not tradable: ${rule.canonicalSymbol}. ${rule.status}")
        val interval = toKrakenIntervalMinutes(timeframe)
        val req = Request.Builder()
            .url("https://api.kraken.com/0/public/OHLC?pair=${rule.exchangePair}&interval=$interval")'''
    candle_new = '''        val rule = resolvePairRule(symbol)
        if (!rule.tradable) error("Kraken pair not tradable: ${rule.canonicalSymbol}. ${rule.status}")
        KrakenRealtimeMarketDataRegistry.ensureOhlc(rule.canonicalSymbol, timeframe)
        val interval = toKrakenIntervalMinutes(timeframe)
        val req = Request.Builder()
            .url("https://api.kraken.com/0/public/OHLC?pair=${rule.exchangePair}&interval=$interval")'''
    exchange = replace_once(exchange, candle_anchor, candle_new, "Kraken OHLC subscription hook")

    candle_tail = '''            val from = kotlin.math.max(0, arr.length() - limit)
            (from until arr.length()).map { idx ->
                val row = arr.getJSONArray(idx)
                Candle(
                    symbol = rule.canonicalSymbol,
                    timeframe = timeframe,
                    openTimeEpochMs = row.getLong(0) * 1000L,
                    open = row.getString(1).toBigDecimal(),
                    high = row.getString(2).toBigDecimal(),
                    low = row.getString(3).toBigDecimal(),
                    close = row.getString(4).toBigDecimal(),
                    volume = row.getString(6).toBigDecimal()
                )
            }
'''
    candle_tail_new = '''            val from = kotlin.math.max(0, arr.length() - limit)
            val restCandles = (from until arr.length()).map { idx ->
                val row = arr.getJSONArray(idx)
                Candle(
                    symbol = rule.canonicalSymbol,
                    timeframe = timeframe,
                    openTimeEpochMs = row.getLong(0) * 1000L,
                    open = row.getString(1).toBigDecimal(),
                    high = row.getString(2).toBigDecimal(),
                    low = row.getString(3).toBigDecimal(),
                    close = row.getString(4).toBigDecimal(),
                    volume = row.getString(6).toBigDecimal()
                )
            }
            KrakenRealtimeMarketDataRegistry.mergeLatestCandle(
                canonicalSymbol = rule.canonicalSymbol,
                timeframe = timeframe,
                restCandles = restCandles,
                limit = limit
            )
'''
    exchange = replace_once(exchange, candle_tail, candle_tail_new, "Kraken OHLC latest-candle merge")
    exchange_path.write_text(exchange, encoding="utf-8")
    print("PATCH |", exchange_path.relative_to(repo))

    controller_path = repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt"
    controller = controller_path.read_text(encoding="utf-8")
    import_anchor = "import com.ksp.cryptobot.exchange.KrakenSpotClient\n"
    controller = replace_once(
        controller,
        import_anchor,
        import_anchor + "import com.ksp.cryptobot.exchange.KrakenRealtimeMarketDataRegistry\n",
        "BotController WS registry import"
    )

    rotation_anchor = '''        if (selected.isEmpty()) {
            updateStatus("Auto symbol discovery produced no tradable/balance-usable candidates. Falling back to configured symbols: ${fallback.joinToString(",")}", "WARN")
            return fallback
        }
        updateStatus("Auto symbol rotation active: ${selected.size} symbols selected from full Kraken universe: ${selected.joinToString(",")}", "LIVE")
        return selected
'''
    rotation_new = '''        if (selected.isEmpty()) {
            KrakenRealtimeMarketDataRegistry.setActiveSymbols(fallback)
            updateStatus("Auto symbol discovery produced no tradable/balance-usable candidates. Falling back to configured symbols: ${fallback.joinToString(",")}", "WARN")
            return fallback
        }
        KrakenRealtimeMarketDataRegistry.setActiveSymbols(selected)
        updateStatus("Auto symbol rotation active: ${selected.size} symbols selected from full Kraken universe: ${selected.joinToString(",")}", "LIVE")
        return selected
'''
    controller = replace_once(controller, rotation_anchor, rotation_new, "active universe WS handoff")
    controller_path.write_text(controller, encoding="utf-8")
    print("PATCH |", controller_path.relative_to(repo))

    service_path = repo / "app/src/main/java/com/ksp/cryptobot/service/BotForegroundService.kt"
    service = service_path.read_text(encoding="utf-8")
    import_anchor = "import com.ksp.cryptobot.governance.ProductionIntelligenceServiceMonitor\n"
    service = replace_once(
        service,
        import_anchor,
        import_anchor + "import com.ksp.cryptobot.exchange.KrakenRealtimeMarketDataRegistry\n",
        "service WS registry import"
    )

    startup_anchor = '''            val startSettings = settingsStore.load()
            if (!awaitUsableNetwork("startup")) return@launch

            if (startSettings.mode == BotMode.LIVE_AUTO) {'''
    startup_new = '''            val startSettings = settingsStore.load()
            if (!awaitUsableNetwork("startup")) return@launch
            configureRealtimeMarketData(startSettings, connectivity.snapshot.usable)

            if (startSettings.mode == BotMode.LIVE_AUTO) {'''
    service = replace_once(service, startup_anchor, startup_new, "service realtime startup")

    network_anchor = '''                val network = connectivity.refresh()
                if (!network.usable) {
                    lastNetworkUsable = false'''
    network_new = '''                val network = connectivity.refresh()
                KrakenRealtimeMarketDataRegistry.onNetworkAvailable(network.usable)
                if (!network.usable) {
                    lastNetworkUsable = false'''
    service = replace_once(service, network_anchor, network_new, "service network feed gate")

    cycle_settings_anchor = '''                val current = settingsStore.load()
                val cycleStart = System.currentTimeMillis()
                try {'''
    cycle_settings_new = '''                val current = settingsStore.load()
                configureRealtimeMarketData(current, network.usable)
                val cycleStart = System.currentTimeMillis()
                try {'''
    service = replace_once(service, cycle_settings_anchor, cycle_settings_new, "service realtime config refresh")

    notification_anchor = '''                    val modeText = "${afterCommands.mode}/${afterCommands.exchangeProvider}"
                    updateNotification(
                        "RUNNING $modeText • net=${network.transports} • next=${selectedDelay}s • signals=${decisions.size}"
                    )'''
    notification_new = '''                    val modeText = "${afterCommands.mode}/${afterCommands.exchangeProvider}"
                    val wsHealth = KrakenRealtimeMarketDataRegistry.health()
                    updateNotification(
                        "RUNNING $modeText • net=${network.transports} • ws=${wsHealth.state}/${wsHealth.systemStatus} • next=${selectedDelay}s • signals=${decisions.size}"
                    )'''
    service = replace_once(service, notification_anchor, notification_new, "service WS notification health")

    stop_anchor = '''    private fun stopBot() {
        statusStore.write("Stop requested. Trading host shutting down.", "WARN")
        controller.stop()'''
    stop_new = '''    private fun stopBot() {
        statusStore.write("Stop requested. Trading host shutting down.", "WARN")
        KrakenRealtimeMarketDataRegistry.stop()
        controller.stop()'''
    service = replace_once(service, stop_anchor, stop_new, "service WS stop")

    destroy_anchor = '''    override fun onDestroy() {
        connectivity.stop()
        loopJob?.cancel()'''
    destroy_new = '''    override fun onDestroy() {
        KrakenRealtimeMarketDataRegistry.stop()
        connectivity.stop()
        loopJob?.cancel()'''
    service = replace_once(service, destroy_anchor, destroy_new, "service WS destroy")

    helper_anchor = '''    private suspend fun awaitUsableNetwork(reason: String): Boolean {'''
    helper_new = '''    private fun configureRealtimeMarketData(
        settings: com.ksp.cryptobot.core.BotSettings,
        networkUsable: Boolean
    ) {
        val shouldRun = settings.enableKrakenWebSocketFeed &&
            settings.exchangeProvider == ExchangeProvider.KRAKEN
        if (!shouldRun) {
            KrakenRealtimeMarketDataRegistry.stop()
            return
        }
        KrakenRealtimeMarketDataRegistry.start()
        KrakenRealtimeMarketDataRegistry.onNetworkAvailable(networkUsable)
        KrakenRealtimeMarketDataRegistry.setActiveSymbols(settings.symbols())
        val health = KrakenRealtimeMarketDataRegistry.health()
        statusStore.write(
            "Kraken WS v2 market-data host: ${health.summary()}",
            if (health.lastError.isBlank()) "INFO" else "WARN"
        )
    }

    private suspend fun awaitUsableNetwork(reason: String): Boolean {'''
    service = replace_once(service, helper_anchor, helper_new, "service WS configure helper")

    service_path.write_text(service, encoding="utf-8")
    print("PATCH |", service_path.relative_to(repo))

    changed = set(os.popen(f'cd "{repo}" && git diff --name-only -- app').read().splitlines())
    untracked = set(os.popen(f'cd "{repo}" && git ls-files --others --exclude-standard -- app').read().splitlines())
    all_changed = changed | untracked
    allowed = {
        NEW_FILE,
        "app/src/main/java/com/ksp/cryptobot/exchange/ExchangeClientsV08.kt",
        "app/src/main/java/com/ksp/cryptobot/core/BotController.kt",
        "app/src/main/java/com/ksp/cryptobot/service/BotForegroundService.kt",
    }
    unexpected = sorted(all_changed - allowed)
    missing = sorted(allowed - all_changed)
    if unexpected:
        fail("Unexpected app changes: " + ", ".join(unexpected))
    if missing:
        fail("Expected M3.2 changes missing: " + ", ".join(missing))

    print("PASS | M3.2 changed only the approved WebSocket integration files.")

if __name__ == "__main__":
    main()
