package com.ksp.cryptobot.completion

import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.core.ExchangeProvider
import com.ksp.cryptobot.core.Timeframe
import com.ksp.cryptobot.exchange.CryptoExchangeClient

data class LiveVerificationResult(
    val name: String,
    val passed: Boolean,
    val detail: String
)

class LiveVerificationEngine {
    suspend fun run(settings: BotSettings, exchange: CryptoExchangeClient): List<LiveVerificationResult> {
        val results = mutableListOf<LiveVerificationResult>()
        suspend fun check(name: String, block: suspend () -> String) {
            val result = runCatching { block() }
            results += LiveVerificationResult(name, result.isSuccess, result.getOrElse { it.message ?: "failed" })
        }
        check("Provider") { "${settings.exchangeProvider} selected; live=${settings.exchangeProvider != ExchangeProvider.PAPER}" }
        check("Balance read") { "assets=${exchange.getAvailableBalances().filterValues { it > java.math.BigDecimal.ZERO }.keys.joinToString(",").ifBlank { "none" }}" }
        check("Portfolio read") { "rows=${exchange.getPortfolioBalances().size}" }
        check("AssetPairs discovery") { "pairs=${exchange.discoverTradableSymbols(settings.autoSymbolQuoteAsset, 25).size}" }
        val probe = settings.symbols().firstOrNull() ?: "BTCEUR"
        check("Symbol validation") { val info = exchange.validateSymbol(probe); "${info.normalizedSymbol} tradable=${info.tradable} pair=${info.exchangePair}" }
        check("Ticker read") { val t = exchange.getTicker(probe); "last=${t.lastPrice} bid=${t.bid} ask=${t.ask}" }
        check("OHLC read") { "candles=${exchange.getCandles(probe, Timeframe.M5, 10).size}" }
        check("Open orders read") { "open=${exchange.getOpenOrders().size}" }
        check("Closed orders read") { "closed=${exchange.getClosedOrders(10).size}" }
        return results
    }
}
