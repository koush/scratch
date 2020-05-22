package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.*
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.client.executor.AsyncHttpConnectSocketExecutor
import com.koushikdutta.scratch.http.client.randomAccess
import com.koushikdutta.scratch.http.server.AsyncHttpRouter
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import com.koushikdutta.scratch.http.server.randomAccessInput
import com.koushikdutta.scratch.parser.readAllString
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StorageTests {
    @Test
    fun testByteStorage() {
        val bb = ByteBufferList().putUtf8String("hello world")
        val storage = BufferStorage(bb)

        async {
            assertEquals("hello world", readAllString(storage::read))
            assertEquals("", readAllString(storage::read))

            storage.seekPosition(1)
            assertEquals("ello world", readAllString(storage::read))
            assertEquals("", readAllString(storage::read))

            val buffer = ByteBufferList()
            storage.readPosition(1, 4, buffer)
            assertEquals("ello", buffer.readUtf8String())
            assertEquals(" world", readAllString(storage::read))
        }
    }

    @Test
    fun testWriteByteStorage() {
        val bb = ByteBufferList().putUtf8String("hello world")
        val storage = BufferStorage(bb)

        async {
            storage.seekPosition(storage.size())
            storage::write.drain("fizzbuzz".createByteBufferList())

            storage.seekPosition(0)
            assertEquals("hello worldfizzbuzz", readAllString(storage::read))

            storage.seekPosition(0)
            storage::write.drain("fizzy".createByteBufferList())
            storage.seekPosition(0)
            assertEquals("fizzy worldfizzbuzz", readAllString(storage::read))

            storage.seekPosition(1)
            storage::write.drain("u".createByteBufferList())
            storage.seekPosition(0)
            assertEquals("fuzzy worldfizzbuzz", readAllString(storage::read))

            storage.seekPosition(1)
            storage::write.drain("o".createByteBufferList())
            storage.seekPosition(1)
            assertEquals("ozzy worldfizzbuzz", readAllString(storage::read))
        }
    }

    @Test
    fun testHttpStorage() {
        val pipeServer = createAsyncPipeServerSocket()
        async {
            val router = AsyncHttpRouter()
            router.randomAccessInput("/") { headers ->
                val bb = ByteBufferList().putUtf8String("hello world")
                val storage = BufferStorage(bb)
                storage
            }

            val httpServer = AsyncHttpServer(router::handle)
            httpServer.listen(pipeServer)
        }

        var done = false
        async {
            val httpClient = AsyncHttpConnectSocketExecutor {
                pipeServer.connect()
            }

            val storage = httpClient.randomAccess("http://example")

            assertEquals("hello world", readAllString(storage::read))
            assertEquals("", readAllString(storage::read))

            storage.seekPosition(1)
            assertEquals("ello world", readAllString(storage::read))
            assertEquals("", readAllString(storage::read))

            val buffer = ByteBufferList()
            storage.readPosition(1, 4, buffer)
            assertEquals("ello", buffer.readUtf8String())
            assertEquals(" world", readAllString(storage::read))
            done = true
        }

        assertTrue(done)
    }
}