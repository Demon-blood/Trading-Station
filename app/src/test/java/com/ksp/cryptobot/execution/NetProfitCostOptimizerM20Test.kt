package com.ksp.cryptobot.execution

import com.ksp.cryptobot.data.AdvancedExecutionEventEntity
import com.ksp.cryptobot.data.ExecutionQualityEntity
import com.ksp.cryptobot.data.GovernanceEventEntity
import com.ksp.cryptobot.data.TradeEntity
import java.math.BigDecimal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetProfitCostOptimizerM20Test {
    private val optimizer = NetProfitCostOptimizer()
    private val now = 1_800_000_000_000L

    @Test fun m20CanNeverPromoteM5Block() {
        val result = optimizer.evaluate(input(economics = economics(allowed = false, netEv = "1.00")))
        assertFalse(result.allowed)
    }

    @Test fun insufficientExecutionSamplesDoNotInventSlippagePenalty() {
        val rows = listOf(execution(0.90), execution(0.90))
        val result = optimizer.evaluate(input(executionQuality = rows))
        assertTrue(result.incrementalObservedSlippageQuote.compareTo(BigDecimal.ZERO) == 0)
        assertTrue(result.allowed)
    }

    @Test fun observedSlippageOnlyChargesExcessAboveM5Model() {
        val rows = List(6) { execution(0.50) }
        val result = optimizer.evaluate(
            input(
                economics = economics(netEv = "0.20", entrySlip = "0.0200", notional = "10.00"),
                executionQuality = rows
            )
        )
        assertTrue(result.incrementalObservedSlippageQuote.compareTo(BigDecimal("0.0300")) == 0)
    }

    @Test fun lifecycleCancelsReduceOpportunityAdjustedEdge() {
        val result = optimizer.evaluate(
            input(
                calibration = ExecutionCalibrationSnapshot(
                    samples = 5,
                    meanFillSeconds = 40.0,
                    meanSlippageBps = 2.0,
                    totalAmendments = 2,
                    totalCancels = 5
                )
            )
        )
        assertTrue(result.combinedExecutionReliability < BigDecimal.ONE)
        assertTrue(result.nonExecutionOpportunityCostQuote > BigDecimal.ZERO)
        assertTrue(result.adjustedNetExpectedValueQuote < result.m5NetExpectedValueQuote)
    }

    @Test fun operationalErrorsCannotImproveReliability() {
        val events = List(5) {
            GovernanceEventEntity(
                timestampEpochMs = now - 1_000L,
                eventType = "order_error",
                symbol = "BTCEUR",
                reason = "test"
            )
        }
        val plans = List(5) {
            AdvancedExecutionEventEntity(
                timestampEpochMs = now - 1_000L,
                eventType = "entry_plan",
                symbol = "BTCEUR",
                blocked = false,
                reason = "submitted"
            )
        }
        val result = optimizer.evaluate(input(governanceEvents = events, advancedExecution = plans))
        assertTrue(result.operationalReliability < BigDecimal.ONE)
        assertTrue(result.adjustedNetExpectedValueQuote < result.m5NetExpectedValueQuote)
    }

    @Test fun explicitInfrastructureCostIsChargedOnce() {
        val result = optimizer.evaluate(
            input(explicitInfrastructureCostQuote = BigDecimal("0.05"))
        )
        assertTrue(result.explicitInfrastructureCostQuote.compareTo(BigDecimal("0.05")) == 0)
        assertTrue(result.adjustedNetExpectedValueQuote.compareTo(BigDecimal("0.45")) == 0)
    }

    @Test fun scarceTradeSlotsUseMeasuredAlternativeBenchmark() {
        val buys = List(3) { index ->
            trade(side = "BUY", ts = now - 10_000L - index, pnl = "0.00")
        }
        val candidates = listOf("0.020", "0.018", "0.022", "0.021", "0.019").map { rate ->
            AdvancedExecutionEventEntity(
                timestampEpochMs = now - 1_000L,
                eventType = "entry_economics",
                symbol = "ETHEUR",
                multiplier = rate.toDouble(),
                blocked = false,
                reason = "positive"
            )
        }
        val result = optimizer.evaluate(
            input(
                economics = economics(netEv = "0.10", notional = "10.00"),
                recentTrades = buys,
                advancedExecution = candidates,
                maxTradesPerDay = 4
            )
        )
        assertTrue(result.opportunityBenchmarkRate > BigDecimal.ZERO)
        assertTrue(result.opportunityGapQuote > BigDecimal.ZERO)
        assertFalse(result.allowed)
    }

    @Test fun abundantTradeSlotsDoNotInventOpportunityBenchmark() {
        val candidates = List(8) {
            AdvancedExecutionEventEntity(
                timestampEpochMs = now - 1_000L,
                eventType = "entry_economics",
                symbol = "ETHEUR",
                multiplier = 0.03,
                blocked = false,
                reason = "positive"
            )
        }
        val result = optimizer.evaluate(input(advancedExecution = candidates, maxTradesPerDay = 10))
        assertTrue(result.opportunityBenchmarkRate.compareTo(BigDecimal.ZERO) == 0)
        assertTrue(result.allowed)
    }

    private fun input(
        economics: TradeEconomicsAssessment = economics(),
        recentTrades: List<TradeEntity> = emptyList(),
        executionQuality: List<ExecutionQualityEntity> = emptyList(),
        advancedExecution: List<AdvancedExecutionEventEntity> = emptyList(),
        governanceEvents: List<GovernanceEventEntity> = emptyList(),
        calibration: ExecutionCalibrationSnapshot? = null,
        maxTradesPerDay: Int = 4,
        explicitInfrastructureCostQuote: BigDecimal = BigDecimal.ZERO
    ) = NetProfitCostInput(
        economics = economics,
        recentTrades = recentTrades,
        executionQuality = executionQuality,
        advancedExecution = advancedExecution,
        governanceEvents = governanceEvents,
        calibration = calibration,
        maxTradesPerDay = maxTradesPerDay,
        currentMode = "LIVE",
        nowEpochMs = now,
        explicitInfrastructureCostQuote = explicitInfrastructureCostQuote
    )

    private fun economics(
        allowed: Boolean = true,
        netEv: String = "0.50",
        notional: String = "10.00",
        entrySlip: String = "0.00"
    ) = TradeEconomicsAssessment(
        allowed = allowed,
        symbol = "BTCEUR",
        strategyId = "BREAKOUT",
        notionalQuote = BigDecimal(notional),
        quantity = BigDecimal("0.001"),
        probabilityWin = BigDecimal("0.60"),
        probabilitySource = "TEST",
        outcomeSamples = 20,
        makerEntry = true,
        feeSource = "TEST_FEE",
        entryFeeRate = BigDecimal("0.0040"),
        exitFeeRate = BigDecimal("0.0080"),
        spreadRate = BigDecimal("0.0010"),
        expectedWinQuote = BigDecimal("1.20"),
        expectedLossQuote = BigDecimal("0.60"),
        grossExpectedValueQuote = BigDecimal("0.80"),
        entryFeeQuote = BigDecimal("0.04"),
        expectedExitFeeQuote = BigDecimal("0.08"),
        spreadCostQuote = BigDecimal("0.01"),
        entrySlippageQuote = BigDecimal(entrySlip),
        expectedExitSlippageQuote = BigDecimal("0.01"),
        externalDecisionCostQuote = BigDecimal.ZERO,
        safetyReserveQuote = BigDecimal("0.025"),
        totalExpectedCostQuote = BigDecimal("0.165"),
        netExpectedValueQuote = BigDecimal(netEv),
        netExpectedValueRate = BigDecimal(netEv).divide(BigDecimal(notional)),
        breakEvenWinProbability = BigDecimal("0.55"),
        riskRewardRatio = BigDecimal("2.0"),
        reason = "test"
    )

    private fun execution(slippagePct: Double) = ExecutionQualityEntity(
        timestampEpochMs = now - 2_000L,
        symbol = "BTCEUR",
        side = "BUY",
        mode = "LIVE",
        orderType = "LIMIT",
        expectedPrice = 100.0,
        actualPrice = 100.0 * (1.0 + slippagePct / 100.0),
        slippagePct = slippagePct,
        notionalQuote = 10.0
    )

    private fun trade(side: String, ts: Long, pnl: String) = TradeEntity(
        symbol = "BTCEUR",
        side = side,
        quantity = "0.001",
        priceEur = "100.00",
        feeEur = "0.01",
        paper = false,
        realizedPnlEur = pnl,
        timestampEpochMs = ts
    )
}
