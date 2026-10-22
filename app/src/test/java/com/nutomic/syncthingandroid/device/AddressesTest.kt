package com.nutomic.syncthingandroid.device

import org.junit.Assert.assertEquals
import org.junit.Test

class AddressesTest {

    @Test
    fun separatorsAreCommasSemicolonsOrSpaces() {
        val expected = listOf("tcp4://192.168.1.67:2222", "dynamic")
        listOf(
            "tcp4://192.168.1.67:2222, dynamic",
            "tcp4://192.168.1.67:2222; dynamic",
            "tcp4://192.168.1.67:2222,dynamic",
            "tcp4://192.168.1.67:2222 dynamic",
            " tcp4://192.168.1.67:2222 ,, dynamic, ",
        ).forEach { assertEquals(it, expected, parseAddresses(it)) }
    }

    @Test
    fun emptyMeansDynamic() {
        assertEquals(listOf("dynamic"), parseAddresses(""))
        assertEquals(listOf("dynamic"), parseAddresses(" ,; "))
    }
}
