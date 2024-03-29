package com.koushikdutta.scratch

import com.koushikdutta.scratch.TestUtils.Companion.countBytes
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.parser.parse
import com.koushikdutta.scratch.parser.readString
import kotlin.test.Test
import kotlin.test.assertEquals

class AsyncReadTests {
    @Test
    fun testAddition() {
        var result = ""
        async {
            val stream1 = ByteBufferList().putUtf8String("Hello").createReader()
            val stream2 = ByteBufferList().putUtf8String("World").createReader()

            val stream3 = stream1 + stream2
            result = stream3.parse().readString()
        }

        assertEquals(result, "HelloWorld")
    }

    @Test
    fun testCount() {
        val random = TestUtils.createRandomRead(1000000)
        var count = 0
        async {
            count = random.countBytes()
        }
        assertEquals(count, 1000000)
    }
}
