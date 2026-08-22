package com.ksp.cryptobot.research

import org.junit.Assert.*
import org.junit.Test

class MarketReferenceNormalizerTest {
    @Test fun usdtToEurMustBeConvertedBeforeDeviation() {
        val ref=MarketReferenceNormalizer.normalize(100.0,"USDT","EUR",0.92)
        assertTrue(ref.valid); assertEquals(92.0,ref.normalizedPrice,0.0001)
        assertEquals(0.0,MarketReferenceNormalizer.deviationPercent(92.0,ref)!!,0.0001)
    }
    @Test fun missingFxFailsNeutralInsteadOfComparingDifferentQuotes() {
        val ref=MarketReferenceNormalizer.normalize(100.0,"USDT","EUR",null)
        assertFalse(ref.valid); assertNull(MarketReferenceNormalizer.deviationPercent(92.0,ref))
    }
    @Test fun absurdExternalNumberFailsSanity() {
        assertFalse(ExternalNumericSanity.plausibleStablecoinLiquidityUsd(51_834_306_629_748_712.0))
    }
}
