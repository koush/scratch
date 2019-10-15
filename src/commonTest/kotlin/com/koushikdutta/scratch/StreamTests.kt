package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.parser.readAllString
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamTests {
    @Test
    fun testAddition() {
        var result = ""
        async {
            val stream1 = ByteBufferList().putUtf8String("Hello").reader()
            val stream2 = ByteBufferList().putUtf8String("World").reader()

            val stream3 = stream1 + stream2
            result = readAllString(stream3)
        }

        assertEquals(result, "HelloWorld")
    }
}