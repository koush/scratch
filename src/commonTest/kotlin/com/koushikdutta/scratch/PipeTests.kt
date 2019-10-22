package com.koushikdutta.scratch

import com.koushikdutta.scratch.TestUtils.Companion.count
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.parser.readAllString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PipeTests {
    @Test
    fun testPipeServer() {
        val server = createAsyncPipeServerSocket()

        var data = ""
        server.acceptAsync {
            val readRef: AsyncRead = {read(it)}
            data = readAllString(readRef)
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
            count += server.connect()::read.count()
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
                count += server.connect()::read.count()
            }
        }

        assertEquals(count, 1000000 * 100)
    }
}
