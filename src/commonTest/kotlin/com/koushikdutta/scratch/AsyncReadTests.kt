package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.parser.readAllString
import kotlin.test.Test
import kotlin.test.assertEquals

import com.koushikdutta.scratch.TestUtils.Companion.countBytes

class AsyncReadTests {
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

    @Test
    fun testCount() {
        val random = TestUtils.createRandomRead(100000000)
        var count = 0
        async {
            count = random.countBytes()
        }
        assertEquals(count, 100000000)
    }
}