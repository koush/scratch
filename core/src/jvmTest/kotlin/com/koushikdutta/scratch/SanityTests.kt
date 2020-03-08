package com.koushikdutta.scratch;

import org.junit.Test;
import java.nio.ByteBuffer
import kotlin.test.assertEquals

class SanityTests {
    @Test
    fun testBinary() {
        val i = 34
        assertEquals(Integer.toBinaryString(i), i.toString(2))
    }
}
