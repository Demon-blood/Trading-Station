package com.ksp.cryptobot

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewUiContractTest {
    @Test fun symbolPairFormattingMatchesPreviewLabels() {
        assertEquals("BTC/EUR", previewPair("BTCEUR"))
        assertEquals("KAS/EUR", previewPair("KAS/EUR"))
    }

    @Test fun detailRoutesKeepCorrectBottomNavigationParent() {
        assertEquals(AppTab.AI, previewParentTab(AppTab.AI_SIGNAL_DETAIL))
        assertEquals(AppTab.PORTFOLIO, previewParentTab(AppTab.POSITIONS))
        assertEquals(AppTab.SETTINGS, previewParentTab(AppTab.SYSTEM_TEST))
    }
}
