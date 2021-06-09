package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.*
import com.koushikdutta.scratch.http.client.executor.AsyncHttpConnectSocketExecutor
import com.koushikdutta.scratch.http.client.randomAccess
import com.koushikdutta.scratch.http.server.AsyncHttpRouter
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import com.koushikdutta.scratch.http.server.randomAccessInput
import com.koushikdutta.scratch.parser.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StorageTests {
    @Test
    fun testByteStorage() {
        val bb = ByteBufferList().putUtf8String("hello world")
        val storage = BufferStorage(bb)

        async {
            assertEquals("hello world", storage.parse().readString())
            assertEquals("", storage.parse().readString())

            storage.seekPosition(1)
            assertEquals("ello world", storage.parse().readString())
            assertEquals("", storage.parse().readString())

            val buffer = ByteBufferList()
            storage.readPosition(1, 4, buffer)
            assertEquals("ello", buffer.readUtf8String())
            assertEquals(" world", storage.parse().readString())
        }
    }

    @Test
    fun testWriteByteStorage() {
        val bb = ByteBufferList().putUtf8String("hello world")
        val storage = BufferStorage(bb)

        async {
            storage.seekPosition(storage.size())
            storage.drain("fizzbuzz".createByteBufferList())

            storage.seekPosition(0)
            assertEquals("hello worldfizzbuzz", storage.parse().readString())

            storage.seekPosition(0)
            storage.drain("fizzy".createByteBufferList())
            storage.seekPosition(0)
            assertEquals("fizzy worldfizzbuzz", storage.parse().readString())

            storage.seekPosition(1)
            storage.drain("u".createByteBufferList())
            storage.seekPosition(0)
            assertEquals("fuzzy worldfizzbuzz", storage.parse().readString())

            storage.seekPosition(1)
            storage.drain("o".createByteBufferList())
            storage.seekPosition(1)
            assertEquals("ozzy worldfizzbuzz", storage.parse().readString())
        }
    }

    @Test
    fun testHttpStorage() {
        val pipeServer = createAsyncPipeServerSocket()
        async {
            val router = AsyncHttpRouter()
            router.randomAccessInput("/") {
                val bb = ByteBufferList().putUtf8String("hello world")
                val storage = BufferStorage(bb)
                storage
            }

            val httpServer = AsyncHttpServer(router::handle)
            httpServer.listenAsync(pipeServer)
        }

        var done = false
        async {
            val httpClient = AsyncHttpConnectSocketExecutor {
                pipeServer.connect()
            }

            val storage = httpClient.randomAccess("http://example")

            assertEquals("hello world", storage.parse().readString())
            assertEquals("", storage.parse().readString())

            storage.seekPosition(1)
            assertEquals("ello world", storage.parse().readString())
            assertEquals("", storage.parse().readString())

            val buffer = ByteBufferList()
            storage.readPosition(1, 4, buffer)
            assertEquals("ello", buffer.readUtf8String())
            assertEquals(" world", storage.parse().readString())
            done = true
        }

        assertTrue(done)
    }
}