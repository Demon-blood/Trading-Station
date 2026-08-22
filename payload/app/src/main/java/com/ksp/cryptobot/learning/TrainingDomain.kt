package com.ksp.cryptobot.learning

import com.ksp.cryptobot.core.BotMode
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.core.ExchangeProvider
import com.ksp.cryptobot.data.TradeEntity

enum class TrainingDomain { PAPER, LIVE, SHADOW, BACKTEST }

object TrainingDomainPolicy {
    fun current(settings: BotSettings): TrainingDomain =
        if (settings.mode == BotMode.PAPER || settings.exchangeProvider == ExchangeProvider.PAPER) TrainingDomain.PAPER
        else TrainingDomain.LIVE

    fun filterTrades(
        trades: List<TradeEntity>,
        requested: TrainingDomain,
        separationEnabled: Boolean
    ): List<TradeEntity> {
        if (!separationEnabled) return trades
        return when (requested) {
            TrainingDomain.PAPER -> trades.filter { it.paper }
            TrainingDomain.LIVE -> trades.filter { !it.paper }
            // Shadow/backtest are never silently inferred from the trade journal.
            TrainingDomain.SHADOW, TrainingDomain.BACKTEST -> emptyList()
        }
    }

    fun symbolKey(symbol: String, domain: TrainingDomain, separated: Boolean): String =
        if (separated) "${domain.name}|${symbol.uppercase()}" else symbol.uppercase()

    fun strategyKey(strategy: String, domain: TrainingDomain, separated: Boolean): String =
        if (separated) "${domain.name}|$strategy" else strategy

    fun stripDomain(key: String): String = key.substringAfter('|', key)

    fun assertNoCrossDomainUse(
        requested: TrainingDomain,
        records: List<TradeEntity>,
        separationEnabled: Boolean
    ) {
        if (!separationEnabled) return
        when (requested) {
            TrainingDomain.PAPER -> require(records.all { it.paper }) { "Cross-domain LIVE trade used in PAPER learning." }
            TrainingDomain.LIVE -> require(records.all { !it.paper }) { "Cross-domain PAPER trade used in LIVE learning." }
            TrainingDomain.SHADOW, TrainingDomain.BACKTEST -> require(records.isEmpty()) {
                "$requested learning cannot consume TradeEntity rows without explicit domain provenance."
            }
        }
    }
}
