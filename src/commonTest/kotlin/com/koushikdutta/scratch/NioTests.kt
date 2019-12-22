package com.koushikdutta.scratch

import com.koushikdutta.scratch.TestUtils.Companion.createRandomRead
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.parser.readAllString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NioTests {
    @Test
    fun testNioWriter() {
        var highWater = false
        val pipe = object : NonBlockingWritePipe(0) {
            override fun writable() {
                highWater = true
            }
        }

        val keepGoing = pipe.write(ByteBufferList().putUtf8String("Hello World"))
        pipe.write(ByteBufferList().putUtf8String("Hello World"))
        pipe.write(ByteBufferList().putUtf8String("Hello World"))
        pipe.end()

        var data = ""
        async {
            data = readAllString({pipe.read(it)})
        }

        assertEquals(data, "Hello WorldHello WorldHello World")

        assertTrue(!keepGoing)
        assertTrue(highWater)
    }

    @Test
    fun testNioWriterWritable() {
        val yielder = Cooperator()
        val pipe = object : NonBlockingWritePipe(0) {
            override fun writable() {
                yielder.resume()
            }
        }

        async {
            for (i in 1..3) {
                val keepGoing = pipe.write(ByteBufferList().putUtf8String("Hello World"))
                assertTrue(!keepGoing)
                yielder.yield()
            }
            pipe.end()
        }

        var data = ""
        async {
            data = readAllString({pipe.read(it)})
        }

        assertEquals(data, "Hello WorldHello WorldHello World")
    }

    @Test
    fun testReadBuffer() {
        val buffer = ByteBufferList()
        async {
            // should immediately fill the buffer with 10mb
            val random = createRandomRead(10000000)
            random(buffer)
        }
        assertEquals(buffer.remaining(), 10000000)
    }
}