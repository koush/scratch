package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.parser.readAllString
import org.junit.Test

class StreamTests {
    @Test
    fun testAddition() {
        var result = ""
        async {
            val stream1 = ByteBufferList().putString("Hello").reader()
            val stream2 = ByteBufferList().putString("World").reader()

            val stream3 = stream1 + stream2
            result = readAllString(stream3)
        }

        assert(result == "HelloWorld")
    }
}