package com.ksp.cryptobot.risk

import com.ksp.cryptobot.core.Candle
import kotlin.math.sqrt

data class CorrelationAssessment(
    val candidateToBtc: Double?,
    val existingBtcBetaPositions: List<String>,
    val allowed: Boolean,
    val reason: String
)

object CorrelationClusterGuard {
    private const val BTC_BETA_THRESHOLD = 0.70

    fun returnCorrelation(a: List<Candle>, b: List<Candle>): Double? {
        val aByTime = a.associateBy { it.openTimeEpochMs }
        val bByTime = b.associateBy { it.openTimeEpochMs }
        val times = aByTime.keys.intersect(bByTime.keys).sorted()
        if (times.size < 31) return null
        val ax = mutableListOf<Double>()
        val bx = mutableListOf<Double>()
        for (i in 1 until times.size) {
            val a0 = aByTime[times[i-1]]!!.close.toDouble()
            val a1 = aByTime[times[i]]!!.close.toDouble()
            val b0 = bByTime[times[i-1]]!!.close.toDouble()
            val b1 = bByTime[times[i]]!!.close.toDouble()
            if (a0 > 0 && b0 > 0) {
                ax += a1 / a0 - 1.0
                bx += b1 / b0 - 1.0
            }
        }
        if (ax.size < 30) return null
        val am = ax.average(); val bm = bx.average()
        var cov = 0.0; var av = 0.0; var bv = 0.0
        ax.indices.forEach { i ->
            val da=ax[i]-am; val db=bx[i]-bm
            cov += da*db; av += da*da; bv += db*db
        }
        if (av <= 0.0 || bv <= 0.0) return null
        return (cov / sqrt(av*bv)).coerceIn(-1.0,1.0)
    }

    fun assessCandidate(
        candidateSymbol: String,
        candidateHistory: List<Candle>,
        btcHistory: List<Candle>,
        openPositionHistories: Map<String,List<Candle>>
    ): CorrelationAssessment {
        if (candidateSymbol.uppercase().startsWith("BTC")) {
            val existing = openPositionHistories.keys.filter { it.uppercase().startsWith("BTC") }
            return CorrelationAssessment(1.0, existing, existing.isEmpty(),
                if(existing.isEmpty()) "BTC cluster free." else "BTC cluster already occupied by ${existing.joinToString()}.")
        }
        val candidateCorr = returnCorrelation(candidateHistory, btcHistory)
            ?: return CorrelationAssessment(null, emptyList(), false,
                "Correlation history insufficient; Turtle BTC-beta cluster entry fails closed until correlation can be measured.")
        if (candidateCorr < BTC_BETA_THRESHOLD) return CorrelationAssessment(candidateCorr, emptyList(), true,
            "Candidate correlation to BTC=${"%.3f".format(candidateCorr)} below BTC-beta threshold=$BTC_BETA_THRESHOLD.")
        val beta = openPositionHistories.mapNotNull { (symbol,hist) ->
            val corr = if(symbol.uppercase().startsWith("BTC")) 1.0 else returnCorrelation(hist,btcHistory)
            if(corr != null && corr >= BTC_BETA_THRESHOLD) symbol else null
        }
        return CorrelationAssessment(candidateCorr,beta,beta.isEmpty(),
            if(beta.isEmpty()) "BTC-beta cluster free; candidate corr=${"%.3f".format(candidateCorr)}."
            else "BTC-beta cluster occupied by ${beta.joinToString()}; candidate corr=${"%.3f".format(candidateCorr)}.")
    }
}
