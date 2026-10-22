package com.nutomic.syncthingandroid.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LogMaskTest {

    private static final String DEVICE_ID = "MFZWI3D-BONSGYC-YLTMRWG-C43ENR5-QXGZDMM-FZWI3DP-BONSGYY-LTMRWAD";

    @Test
    public void masksFullDeviceIdsAndPeerAddresses() {
        assertEquals("Connected to MFZWI3D-XXXXXXX-... at tcp://93.184.x.x:22000",
                LogMask.maskLine("Connected to " + DEVICE_ID + " at tcp://93.184.216.34:22000"));
        assertEquals("relay [x:x]:22067",
                LogMask.maskLine("relay [2001:db8::1]:22067"));
    }

    @Test
    public void keepsLocalAddressesVersionsAndShortIds() {
        String line = "GUI https://127.0.0.1:8384, listen 0.0.0.0:22000 and [::1]:8384, v2.1.7.0, device MFZWI3D";
        assertEquals(line, LogMask.maskLine(line));
    }
}
