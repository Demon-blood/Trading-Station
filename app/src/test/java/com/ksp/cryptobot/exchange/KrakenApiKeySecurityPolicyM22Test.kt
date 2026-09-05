package com.ksp.cryptobot.exchange

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KrakenApiKeySecurityPolicyM22Test {
    private fun safeInfo(
        permissions: Set<String> = KrakenApiKeySecurityPolicy.REQUIRED_LIVE_PERMISSIONS,
        validUntilEpochSeconds: Long = 0L,
        ipAllowlist: List<String> = emptyList()
    ) = KrakenApiKeySecurityInfo(
        keyFingerprint = "abcdef0123456789",
        keyName = "cts-trading",
        permissions = permissions,
        validUntilEpochSeconds = validUntilEpochSeconds,
        ipAllowlist = ipAllowlist
    )

    @Test fun exactLeastPrivilegeKeyIsSafe() {
        val result = KrakenApiKeySecurityPolicy.assess(
            safeInfo(),
            nowEpochSeconds = 1_000L,
            checkedAtEpochMs = 2_000L
        )
        assertTrue(result.safeForLive)
        assertTrue(result.dangerousPermissions.isEmpty())
        assertTrue(result.missingRequiredPermissions.isEmpty())
    }

    @Test fun withdrawFundsHardBlocksLive() {
        val info = safeInfo(
            KrakenApiKeySecurityPolicy.REQUIRED_LIVE_PERMISSIONS + "withdraw-funds"
        )
        val result = KrakenApiKeySecurityPolicy.assess(info)
        assertFalse(result.safeForLive)
        assertTrue("withdraw-funds" in result.dangerousPermissions)
    }

    @Test fun addWithdrawAddressHardBlocksLive() {
        val info = safeInfo(
            KrakenApiKeySecurityPolicy.REQUIRED_LIVE_PERMISSIONS + "add-withdraw-address"
        )
        val result = KrakenApiKeySecurityPolicy.assess(info)
        assertFalse(result.safeForLive)
        assertTrue("add-withdraw-address" in result.dangerousPermissions)
    }

    @Test fun updateWithdrawAddressHardBlocksLive() {
        val info = safeInfo(
            KrakenApiKeySecurityPolicy.REQUIRED_LIVE_PERMISSIONS + "update-withdraw-address"
        )
        val result = KrakenApiKeySecurityPolicy.assess(info)
        assertFalse(result.safeForLive)
        assertTrue("update-withdraw-address" in result.dangerousPermissions)
    }

    @Test fun missingModifyTradesBlocksLive() {
        val info = safeInfo(
            KrakenApiKeySecurityPolicy.REQUIRED_LIVE_PERMISSIONS - "modify-trades"
        )
        val result = KrakenApiKeySecurityPolicy.assess(info)
        assertFalse(result.safeForLive)
        assertTrue("modify-trades" in result.missingRequiredPermissions)
    }

    @Test fun expiredKeyBlocksLive() {
        val result = KrakenApiKeySecurityPolicy.assess(
            safeInfo(validUntilEpochSeconds = 999L),
            nowEpochSeconds = 1_000L
        )
        assertFalse(result.safeForLive)
        assertTrue(result.expired)
    }

    @Test fun benignExtraPermissionIsReportedButDoesNotGrantAuthority() {
        val result = KrakenApiKeySecurityPolicy.assess(
            safeInfo(KrakenApiKeySecurityPolicy.REQUIRED_LIVE_PERMISSIONS + "query-ledger")
        )
        assertTrue(result.safeForLive)
        assertTrue("query-ledger" in result.extraPermissions)
    }

    @Test fun mobileKeyWithoutIpAllowlistIsWarningNotHardBlock() {
        val result = KrakenApiKeySecurityPolicy.assess(safeInfo(ipAllowlist = emptyList()))
        assertTrue(result.safeForLive)
        assertFalse(result.ipRestricted)
    }
}
