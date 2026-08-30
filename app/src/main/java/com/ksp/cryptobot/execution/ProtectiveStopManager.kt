package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.data.*
import com.ksp.cryptobot.exchange.CryptoExchangeClient
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Exchange-level protection for sourced live entries.
 *
 * The app attaches a Kraken conditional stop to the primary BUY when possible. After a confirmed
 * fill this manager verifies that a live SELL stop exists. If it is missing it attempts a standalone
 * stop. If protection still cannot be established, the fail-safe action is to flatten the newly
 * opened quantity with a market SELL rather than leave a knowingly unprotected live position.
 */
class ProtectiveStopManager(
    private val appDao: AppDao,
    private val governanceDao: GovernanceDao
) {
    data class ProtectionResult(
        val protected: Boolean,
        val flattened: Boolean,
        val pendingEmergencyExit: Boolean,
        val activeStopOrderIds: List<String>,
        val reason: String
    )

    suspend fun protectOrFlatten(
        settings: BotSettings,
        exchange: CryptoExchangeClient,
        symbol: String,
        quantity: BigDecimal,
        entryPrice: BigDecimal,
        stopPrice: BigDecimal,
        strategyId: String,
        paper: Boolean
    ): ProtectionResult {
        if (paper || settings.mode == BotMode.PAPER) {
            return ProtectionResult(true,false,false,emptyList(),"PAPER uses the persisted source stop with the automatic paper lifecycle; no real exchange order is sent.")
        }
        if (quantity <= BigDecimal.ZERO || stopPrice <= BigDecimal.ZERO || stopPrice >= entryPrice) {
            return emergencyFlatten(settings,exchange,symbol,quantity,entryPrice,strategyId,"invalid/missing technical stop after confirmed entry: entry=$entryPrice stop=$stopPrice")
        }
        repeat(3) { attempt ->
            val existing = activeStops(exchange,symbol,stopPrice)
            val covered = existing.fold(BigDecimal.ZERO) { acc,o -> acc + o.remainingQuantity }
            if (covered >= quantity.multiply(BigDecimal("0.98"))) {
                clearUnprotectedState(symbol)
                return ProtectionResult(true,false,false,existing.map { it.exchangeOrderId },"Exchange protective stop verified after confirmed entry. covered=$covered requested=$quantity stop=$stopPrice attempt=${attempt+1}.")
            }
            if (attempt < 2) delay(250)
        }
        val existingBeforeStandalone = activeStops(exchange, symbol, stopPrice)
        val coveredBeforeStandalone = existingBeforeStandalone.fold(BigDecimal.ZERO) { acc, o -> acc + o.remainingQuantity }
        val missingCoverage = quantity.subtract(coveredBeforeStandalone).max(BigDecimal.ZERO)
        if (missingCoverage <= quantity.multiply(BigDecimal("0.02"))) {
            clearUnprotectedState(symbol)
            return ProtectionResult(true,false,false,existingBeforeStandalone.map { it.exchangeOrderId },"Protective stop coverage already sufficient after refresh. covered=$coveredBeforeStandalone requested=$quantity.")
        }
        val standalone = runCatching {
            exchange.placeOrder(OrderRequest(
                symbol=symbol,
                side=OrderSide.SELL,
                quantity=missingCoverage,
                limitPrice=stopPrice,
                orderType=OrderType.STOP_LOSS,
                clientOrderId="ksp-protect-${symbol.lowercase()}-${System.currentTimeMillis()}",
                reduceOnly=true,
                purpose="PROTECTIVE_STOP strategy=$strategyId"
            ))
        }.getOrNull()
        if (standalone != null) {
            repeat(3) { attempt ->
                val existing = activeStops(exchange,symbol,stopPrice)
                val covered = existing.fold(BigDecimal.ZERO) { acc,o -> acc + o.remainingQuantity }
                if (covered >= quantity.multiply(BigDecimal("0.98"))) {
                    clearUnprotectedState(symbol)
                    return ProtectionResult(true,false,false,existing.map { it.exchangeOrderId },"Standalone protective stop verified. covered=$covered requested=$quantity stop=$stopPrice order=${standalone.exchangeOrderId} attempt=${attempt+1}.")
                }
                if (attempt < 2) delay(250)
            }
        }
        return emergencyFlatten(settings,exchange,symbol,quantity,entryPrice,strategyId,"exchange stop acknowledgement/coverage could not be verified")
    }

    suspend fun cancelProtectiveStops(exchange: CryptoExchangeClient, symbol: String): Pair<Boolean,List<LiveOrderInfo>> {
        val stops = activeStops(exchange,symbol,null)
        if (stops.isEmpty()) return true to emptyList()
        val failed = mutableListOf<String>()
        for (order in stops) if (!runCatching { exchange.cancelOrder(order.exchangeOrderId) }.getOrDefault(false)) failed += order.exchangeOrderId
        if (failed.isNotEmpty()) return false to stops
        repeat(3) { attempt ->
            val remain = activeStops(exchange,symbol,null)
            if (remain.isEmpty()) return true to stops
            if (attempt < 2) delay(150)
        }
        return false to stops
    }

    suspend fun restoreAfterManagedExit(
        settings: BotSettings,
        exchange: CryptoExchangeClient,
        symbol: String,
        remainingQuantity: BigDecimal,
        entryPrice: BigDecimal,
        stopPrice: BigDecimal,
        strategyId: String,
        paper: Boolean
    ): ProtectionResult {
        if (remainingQuantity <= BigDecimal.ZERO) return ProtectionResult(true,false,false,emptyList(),"Position fully exited; no stop restoration required.")
        return protectOrFlatten(settings,exchange,symbol,remainingQuantity,entryPrice,stopPrice,strategyId,paper)
    }

    private suspend fun activeStops(exchange: CryptoExchangeClient, symbol: String, expectedStop: BigDecimal?): List<LiveOrderInfo> {
        val normalized=symbol.uppercase().replace("/","").replace("-","")
        return runCatching { exchange.getOpenOrders() }.getOrDefault(emptyList()).filter { o ->
            val same=o.symbol.uppercase().replace("/","").replace("-","")==normalized
            val stopMatch=expectedStop==null || expectedStop<=BigDecimal.ZERO || priceClose(o.price,expectedStop)
            same && o.side==OrderSide.SELL && o.orderType==OrderType.STOP_LOSS && o.remainingQuantity>BigDecimal.ZERO && stopMatch
        }
    }

    private fun priceClose(a:BigDecimal,b:BigDecimal):Boolean {
        if(a<=BigDecimal.ZERO||b<=BigDecimal.ZERO)return false
        return a.subtract(b).abs().divide(b,8,RoundingMode.HALF_UP)<=BigDecimal("0.0025")
    }

    private suspend fun emergencyFlatten(
        settings:BotSettings,
        exchange:CryptoExchangeClient,
        symbol:String,
        quantity:BigDecimal,
        entryPrice:BigDecimal,
        strategyId:String,
        cause:String
    ):ProtectionResult {
        markProtectionFailure(symbol,strategyId,cause)
        if(quantity<=BigDecimal.ZERO) return ProtectionResult(false,false,false,emptyList(),"UNPROTECTED_POSITION and no quantity available for emergency flatten: $cause")
        val result=runCatching { exchange.placeOrder(OrderRequest(
            symbol=symbol,side=OrderSide.SELL,quantity=quantity,orderType=OrderType.MARKET,
            clientOrderId="ksp-emergency-${symbol.lowercase()}-${System.currentTimeMillis()}",reduceOnly=true,
            purpose="EMERGENCY_FLATTEN_UNPROTECTED strategy=$strategyId cause=${cause.take(160)}"
        )) }.getOrNull()
        if(result!=null && result.executedQuantity>BigDecimal.ZERO && result.averagePrice>BigDecimal.ZERO){
            val realized=if(result.realizedPnlQuote!=BigDecimal.ZERO)result.realizedPnlQuote else result.averagePrice.subtract(entryPrice).multiply(result.executedQuantity).subtract(result.fee)
            appDao.insertTrade(TradeEntity(
                symbol=result.symbol,side=OrderSide.SELL.name,quantity=result.executedQuantity.toPlainString(),priceEur=result.averagePrice.toPlainString(),feeEur=result.fee.toPlainString(),paper=false,
                realizedPnlEur=realized.toPlainString(),aiReason="Emergency protection failure flatten [$strategyId]: $cause",clientOrderId="protection-emergency-${System.currentTimeMillis()}",exchangeOrderId=result.exchangeOrderId,timestampEpochMs=System.currentTimeMillis()
            ))
            appDao.updatePositionStatus(symbol,"EMERGENCY_EXIT_FILLED",System.currentTimeMillis())
            governanceDao.putState(ProductionIntelligenceStateEntity("UNPROTECTED_POSITION:${symbol.uppercase()}","FLATTENED; strategy=$strategyId; cause=$cause; order=${result.exchangeOrderId}; timestamp=${System.currentTimeMillis()}"))
            return ProtectionResult(false,true,false,emptyList(),"Protective stop could not be verified; fail-safe MARKET flatten filled order=${result.exchangeOrderId} qty=${result.executedQuantity}.")
        }
        if(result!=null){
            appDao.updatePositionStatus(symbol,"EMERGENCY_EXIT_PENDING",System.currentTimeMillis())
            governanceDao.putState(ProductionIntelligenceStateEntity("UNPROTECTED_POSITION:${symbol.uppercase()}","EMERGENCY_EXIT_PENDING; strategy=$strategyId; cause=$cause; order=${result.exchangeOrderId}; timestamp=${System.currentTimeMillis()}"))
            return ProtectionResult(false,false,true,emptyList(),"Protective stop absent; emergency MARKET exit accepted but fill not yet confirmed. order=${result.exchangeOrderId}.")
        }
        return ProtectionResult(false,false,false,emptyList(),"UNPROTECTED_POSITION: protective stop and emergency flatten both failed. $cause")
    }

    private suspend fun markProtectionFailure(symbol:String,strategyId:String,cause:String){
        governanceDao.insertEvent(GovernanceEventEntity(
            eventType="protective_stop_failure",symbol=symbol,strategy=strategyId,mode="LIVE",severity="CRITICAL",blocked=true,sizeMultiplier=0.0,
            reason="UNPROTECTED_POSITION: $cause. Fail-safe emergency flatten invoked."
        ))
        governanceDao.putState(ProductionIntelligenceStateEntity("UNPROTECTED_POSITION:${symbol.uppercase()}","ACTIVE; strategy=$strategyId; cause=$cause; timestamp=${System.currentTimeMillis()}"))
    }

    private suspend fun clearUnprotectedState(symbol:String){
        governanceDao.putState(ProductionIntelligenceStateEntity("UNPROTECTED_POSITION:${symbol.uppercase()}","CLEAR; timestamp=${System.currentTimeMillis()}"))
    }
}
