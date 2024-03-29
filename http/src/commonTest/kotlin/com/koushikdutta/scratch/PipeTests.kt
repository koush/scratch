package com.koushikdutta.scratch

import com.koushikdutta.scratch.TestUtils.Companion.countBytes
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.createByteBufferList
import com.koushikdutta.scratch.parser.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PipeTests {
    @Test
    fun testPipeServer() {
        val server = createAsyncPipeServerSocket()

        var data = ""
        server.acceptAsync {
            data = this.parse().readString()
        }

        async {
            val client = server.connect()
            client.write(ByteBufferList().putUtf8String("hello world"))
            client.close()
        }

        assertEquals(data, "hello world")
    }

    @Test
    fun testByteBufferAllocations() {
        val start = ByteBufferList.totalObtained
        val server = createAsyncPipeServerSocket()

        server.acceptAsync {
            val buffer = ByteBufferList()
            val random = TestUtils.createRandomRead(10000000)
            while (random(buffer)) {
                write(buffer)
                assertTrue(buffer.isEmpty)
            }
            close()
        }

        var count = 0
        async {
            count += server.connect().countBytes()
        }

        assertEquals(count, 10000000)
        assertTrue(ByteBufferList.totalObtained - start < 50000)
    }

    @Test
    fun testServerALot() {
        val server = createAsyncPipeServerSocket()

        server.acceptAsync {
            val buffer = ByteBufferList()
            val random = TestUtils.createRandomRead(1000000)
            while (random(buffer)) {
                write(buffer)
                assertTrue(buffer.isEmpty)
            }
            close()
        }

        var count = 0
        for (i in 1..100) {
            async {
                count += server.connect().countBytes()
            }
        }

        assertEquals(count, 1000000 * 100)
    }

    @Test
    fun testInterruptRead() {
        val pipe = PipeSocket()

        var done = false

        async {
            val buffer = ByteBufferList()
            assertTrue(pipe.read(buffer))
            assertTrue(buffer.isEmpty)
            assertTrue(pipe.read(buffer))
            assertEquals(buffer.readUtf8String(), "hello")
            assertTrue(pipe.read(buffer))
            assertTrue(buffer.isEmpty)
            assertTrue(pipe.read(buffer))
            assertEquals(buffer.readUtf8String(), "world")
            done = true
        }

        async {
            pipe.interruptRead()
            pipe.write("hello".createByteBufferList())
            pipe.interruptRead()
            pipe.write("world".createByteBufferList())
        }

        assertTrue(done)
    }


    @Test
    fun testInterruptWrite() {
        val pipe = PipeSocket()

        var done = false

        async {
            val buffer = ByteBufferList()
            buffer.putUtf8String("hello")
            pipe.write(buffer)
            assertEquals(buffer.peekUtf8String(), "hello")
            pipe.write(buffer)
        }

        async {
            pipe.interruptWrite()
            val buffer = ByteBufferList()
            assertTrue(pipe.read(buffer))
            assertEquals(buffer.readUtf8String(), "hello")
            done = true
        }

        assertTrue(done)
    }
}
