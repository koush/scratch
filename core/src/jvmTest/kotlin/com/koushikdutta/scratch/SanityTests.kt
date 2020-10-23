package com.koushikdutta.scratch;

import org.junit.Test
import java.net.URI
import kotlin.test.assertEquals

class SanityTests {
    @Test
    fun testBinary() {
        val i = 34
        assertEquals(Integer.toBinaryString(i), i.toString(2))
    }

    @Test
    fun testWeirdUri() {
        val uri = URI.create("foo:3333")
        println(uri)
    }
}
