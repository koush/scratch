package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.WritableBuffers
import com.koushikdutta.scratch.buffers.allocateByteBuffer
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.client.AsyncHttpClientSession
import com.koushikdutta.scratch.http.client.AsyncHttpClientTransport
import com.koushikdutta.scratch.http.client.middleware.AsyncHttpClientMiddleware
import com.koushikdutta.scratch.http.server.AsyncHttpRouter
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import com.koushikdutta.scratch.http.server.randomAccessInput
import com.koushikdutta.scratch.parser.readAllString
import randomAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BufferStorage(buffer: ByteBufferList) : AsyncRandomAccessInput, AsyncAffinity by AsyncAffinity.NO_AFFINITY {
    val byteBuffer = allocateByteBuffer(buffer.remaining())

    init {
        byteBuffer.put(buffer.readByteBuffer())
        byteBuffer.flip()
    }

    override suspend fun size(): Long {
        return byteBuffer.capacity().toLong()
    }

    override suspend fun getPosition(): Long {
        return byteBuffer.position().toLong()
    }

    override suspend fun setPosition(position: Long) {
        byteBuffer.position(position.toInt())
    }

    override suspend fun readPosition(position: Long, length: Long, buffer: WritableBuffers): Boolean {
        byteBuffer.position(position.toInt())
        byteBuffer.limit(byteBuffer.position() + length.toInt())
        if (!byteBuffer.hasRemaining())
            return false

        buffer.putAllocatedBuffer(length.toInt()) {
            it.put(byteBuffer)
        }

        return true
    }

    override suspend fun read(buffer: WritableBuffers): Boolean {
        byteBuffer.limit(byteBuffer.capacity())
        if (!byteBuffer.hasRemaining())
            return false
        buffer.putAllocatedBuffer(byteBuffer.remaining()) {
            it.put(byteBuffer)
        }
        return true
    }

    var closed = false
    override suspend fun close() {
        closed = true
    }
}

class StorageTests {
    @Test
    fun testByteStorage() {
        val bb = ByteBufferList().putUtf8String("hello world")
        val storage = BufferStorage(bb)

        async {
            assertEquals("hello world", readAllString(storage::read))
            assertEquals("", readAllString(storage::read))

            storage.setPosition(1)
            assertEquals("ello world", readAllString(storage::read))
            assertEquals("", readAllString(storage::read))

            val buffer = ByteBufferList()
            storage.readPosition(1, 4, buffer)
            assertEquals("ello", buffer.readUtf8String())
            assertEquals(" world", readAllString(storage::read))
        }
    }

    @Test
    fun testHttpStorage() {
        val pipeServer = createAsyncPipeServerSocket()
        async {
            val router = AsyncHttpRouter()
            router.randomAccessInput("/") { headers, request, matchResult ->
                val bb = ByteBufferList().putUtf8String("hello world")
                val storage = BufferStorage(bb)
                storage
            }

            val httpServer = AsyncHttpServer(router::handle)
            httpServer.listen(pipeServer)
        }

        var done = false
        async {
            val httpClient = AsyncHttpClient()
            httpClient.middlewares.add(0, object : AsyncHttpClientMiddleware() {
                override suspend fun connectSocket(session: AsyncHttpClientSession): Boolean {
                    session.transport = AsyncHttpClientTransport(pipeServer.connect())
                    return true
                }
            })

            val storage = httpClient.randomAccess("http://example")

            assertEquals("hello world", readAllString(storage::read))
            assertEquals("", readAllString(storage::read))

            storage.setPosition(1)
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