package com.nutomic.syncthingandroid.http

import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiRequestTest {

    @Test
    fun requestsArePinnedToLoopbackWithTheConfiguredPort() {
        assertEquals("https://127.0.0.1:8385/rest", forceLoopbackHost(URL("https://0.0.0.0:8385/rest")).toString())
        assertEquals("https://127.0.0.1:8384/", forceLoopbackHost(URL("https://192.168.1.2/")).toString())
    }

    @Test
    fun charsetIsDeclaredOneOrUtf8ForJsonOrLatin1() {
        assertEquals(Charsets.UTF_8, charsetOf("application/json; charset=utf-8"))
        assertEquals(Charsets.UTF_16, charsetOf("text/plain; charset=UTF-16"))
        assertEquals(Charsets.UTF_8, charsetOf("application/json"))
        assertEquals(Charsets.ISO_8859_1, charsetOf("text/html"))
        assertEquals(Charsets.ISO_8859_1, charsetOf(null))
        assertEquals(Charsets.ISO_8859_1, charsetOf("text/plain; charset=no-such-charset"))
    }
}
