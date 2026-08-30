package com.ksp.cryptobot.exchange

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KrakenClientOrderIdM13Test {
    @Test fun generatedIdsUseKrakenNativeLongUuidFormat() {
        val id = KrakenClientOrderId.newId()
        assertTrue(id.matches(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")))
        assertEquals(id, KrakenClientOrderId.normalize(id))
    }

    @Test fun generatedIdsAreUniqueAcrossBurst() {
        val ids = (1..1000).map { KrakenClientOrderId.newId() }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun permissionHintExplainsKrakenWebsocketPermission() {
        val hinted = KrakenPrivatePermissionHints.describe("EGeneral:Permission denied")
        assertTrue(hinted.contains("WebSocket interface - On"))
    }
}
