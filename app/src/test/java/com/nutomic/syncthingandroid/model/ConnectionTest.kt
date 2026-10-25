package com.nutomic.syncthingandroid.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionTest {

    @Test
    fun transferRateIsComputedOverTheRealInterval() {
        val previous = Connection().apply { inBytesTotal = 1_000; outBytesTotal = 5_000 }
        val current = Connection().apply { inBytesTotal = 1_500; outBytesTotal = 4_000 }
        // 500 bytes in half a second; a counter that went down gives no negative rate.
        current.setTransferRate(previous, 500)
        assertEquals(8_000L, current.inBits)
        assertEquals(0L, current.outBits)
    }
}
