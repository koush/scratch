package com.koushikdutta.scratch;

import org.junit.Test
import java.io.File
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
        val uri = URI.create("/testbar")
        println(uri)
    }

    @Test
    fun testFileExt() {
        println(File("/tmp/bar", ".tmp"))
    }
}
