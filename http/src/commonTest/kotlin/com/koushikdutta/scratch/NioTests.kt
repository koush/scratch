package com.koushikdutta.scratch

import com.koushikdutta.scratch.TestUtils.Companion.createRandomRead
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.parser.*
import kotlin.test.*

class NioTests {
    @Test
    fun testNioWriter() {
        var highWater = false
        val pipe = NonBlockingWritePipe(0) {
            highWater = true
        }

        // start reading first, otherwise the entire data will be read in one go
        // incuding the pipe ending, so writable will never be triggered.
        var data = ""
        async {
            data = pipe.parse().readString()
        }

        val keepGoing = pipe.write(ByteBufferList().putUtf8String("Hello World"))
        pipe.write(ByteBufferList().putUtf8String("Hello World"))
        pipe.write(ByteBufferList().putUtf8String("Hello World"))
        pipe.end()

        assertEquals(data, "Hello WorldHello WorldHello World")

        assertTrue(!keepGoing)
        assertTrue(highWater)
    }

    @Test
    fun testNioWriter2() {
        var highWater = false
        val pipe = NonBlockingWritePipe(0) {
            highWater = true
        }

        val keepGoing = pipe.write(ByteBufferList().putUtf8String("Hello World"))
        pipe.write(ByteBufferList().putUtf8String("Hello World"))
        pipe.write(ByteBufferList().putUtf8String("Hello World"))
        pipe.end()

        // start reading after end, to ensure data after read is still available.
        var data = ""
        async {
            data = pipe.parse().readString()
        }

        assertEquals(data, "Hello WorldHello WorldHello World")

        assertTrue(!keepGoing)
        assertTrue(highWater)
    }

    @Test
    fun testNioWriterWritable() {
        val yielder = Yielder()
        val pipe = NonBlockingWritePipe(0) {
            yielder.resume()
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
            data = pipe.parse().readString()
        }

        assertEquals(data, "Hello WorldHello WorldHello World")
    }

    @Test
    fun testReadBuffer() {
        val buffer = ByteBufferList()
        async {
            // should immediately fill the buffer with 10mb
            val random = createRandomRead(20000000).buffer(10000000)
            random(buffer)
        }
        assertEquals(buffer.remaining(), 10000000)
    }

    @Test
    fun testDoubleReadError() {
        val pipe = NonBlockingWritePipe(0) {
        }

        async {
            try {
                pipe.parse().readString()
            }
            catch (throwable: IOException) {
                return@async
            }
            fail("exception expected")
        }

        // reading again with another read in progress should succeed here, and cause the previous read to IOException
        var gotData = false
        async {
            pipe.parse().readString()
            gotData = true
        }

        assertFalse(gotData)
    }

    @Test
    fun testNioDoubleFinish() {
        val pipe = NonBlockingWritePipe {}

        assertTrue(pipe.end())
        assertFalse(pipe.end())
    }

    @Test
    fun testNioDoubleRaiseFinish() {
        val pipe = NonBlockingWritePipe {}

        assertTrue(pipe.end(Exception()))
        assertFalse(pipe.end())
    }

    @Test
    fun testBlockingDoubleFinish() {
        val pipe = BlockingWritePipe {}
        assertTrue(pipe.close())
        assertFalse(pipe.close())
    }

    @Test
    fun testBlockingDoubleRaiseFinish() {
        val pipe = BlockingWritePipe {}

        assertTrue(pipe.close(Exception()))
        assertFalse(pipe.close())
    }
}