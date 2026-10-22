package com.nutomic.syncthingandroid.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTest {

    private fun idIsValid(id: String?) = Device().apply { deviceID = id }.checkDeviceID()

    @Test
    fun deviceIdAcceptsSyncthingFormats() {
        // Test vector of lib/protocol/deviceid_test.go in Syncthing.
        assertTrue(idIsValid("P56IOI7-MZJNU2Y-IQGDREY-DM2MGTI-MGL3BXN-PQ6W5BM-TBBZ4TJ-XZWICQ2"))
        assertTrue(idIsValid("p56ioi7mzjnu2yiqgdreydm2mgtimgl3bxnpq6w5bmtbbz4tjxzwicq2"))
        assertTrue(idIsValid("P56IOI7 MZJNU2Y IQGDREY DM2MGTI MGL3BXN PQ6W5BM TBBZ4TJ XZWICQ2"))
    }

    @Test
    fun deviceIdRejectsWrongCheckCharacterLengthAndEmpty() {
        assertFalse(idIsValid("P56IOI7-MZJNU2Y-IQGDREY-DM2MGTI-MGL3BXN-PQ6W5BM-TBBZ4TJ-XZWICQ3"))
        assertFalse(idIsValid("P56IOI7-MZJNU2Y"))
        assertFalse(idIsValid(""))
        assertFalse(idIsValid(null))
    }

    @Test
    fun addressesAcceptedFormerSelfTest() {
        listOf(
            "dynamic",
            "tcp://127.0.0.1:4000",
            "tcp4://127.0.0.1:4000",
            "tcp6://127.0.0.1:4000",
            "tcp4://127.0.0.1",
            "tcp://[2001:db8::23:42]",
            "tcp://[2001:db8::23:42]:12345",
            "tcp://myserver",
            "tcp://myserver:12345",
            "relay://stlocal:22067/?id=ID-REDACTED&pingInterval=30s&networkTimeout=2m0s&sessionLimitBps=0" +
                "&globalLimitBps=0&statusAddr=:22070&providedBy=REDACTED",
            "relay://stlocal:22067",
            "quic://127.0.0.1",
            "quic://127.0.0.1:24000",
            "quic4://127.0.0.1:24000",
            "quic6://127.0.0.1:24000",
        ).forEach { assertTrue(it, Device.checkDeviceAddress(it)) }
    }

    @Test
    fun addressesRejectedFormerSelfTest() {
        listOf(
            "tcp://myserver:",
            "tcp8://127.0.0.1",
            "udp4://127.0.0.1",
            "tcp://",
            "tcp://tcp://myserver",
            "tcp://:4000",
            "tcp://myserver:70000",
        ).forEach { assertFalse(it, Device.checkDeviceAddress(it)) }
    }

    @Test
    fun deviceWithoutAddressesIsInvalid() {
        assertFalse(Device().checkDeviceAddresses())
        assertTrue(Device().apply { addresses = mutableListOf("dynamic", "tcp://myserver:22000") }.checkDeviceAddresses())
    }
}
