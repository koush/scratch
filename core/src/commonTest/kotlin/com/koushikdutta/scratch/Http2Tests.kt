package com.koushikdutta.scratch

import com.koushikdutta.scratch.TestUtils.Companion.createUnboundRandomRead
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.http.Methods
import com.koushikdutta.scratch.http.StatusCode
import com.koushikdutta.scratch.http.body.BinaryBody
import com.koushikdutta.scratch.http.body.Utf8StringBody
import com.koushikdutta.scratch.http.client.middleware.createContentLengthPipe
import com.koushikdutta.scratch.http.http2.Http2Connection
import com.koushikdutta.scratch.http.http2.acceptHttpAsync
import com.koushikdutta.scratch.http.http2.connect
import com.koushikdutta.scratch.parser.readAllString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class Http2Tests {
    @Test
    fun testConnection() {
        val pair = createAsyncPipeSocketPair()

        async {
            Http2Connection(pair.second, false)
            .acceptHttpAsync {
                StatusCode.OK(body = Utf8StringBody("Hello World"))
            }
            .processMessages()
        }

        var data = ""
        async {
            val client = Http2Connection(pair.first, true)
            client.processMessagesAsync()
            val connected = client.connect(Methods.GET("https://example.com/"))
            data = readAllString({connected.read(it)})
        }

        assertEquals(data, "Hello World")
    }


    // this will test the connection flow control.
    // default write window is 16mb.
    @Test
    fun testBigResponse() {
        val pair = createAsyncPipeSocketPair()

        val random = Random.Default
        var sent = 0
        // 100 mb body
        val serverDigest = CrappyDigest.getInstance()
        // generate ~100mb of random data and digest it.
        val packetSize = 10000
        val body: AsyncRead = AsyncReader {
            it.putAllocatedBytes(packetSize) { bytes, bytesOffset ->
                random.nextBytes(bytes, bytesOffset, bytesOffset + packetSize)
                sent += packetSize
                serverDigest.update(bytes, bytesOffset, bytesOffset + packetSize)
            }
            true
        }.pipe(createContentLengthPipe(100000000))

        val clientDigest = CrappyDigest.getInstance()
        var received = 0
        async {
            val client = Http2Connection(pair.first, true)
            client.processMessagesAsync()
            val connected = client.connect(Methods.GET("https://example.com/"))
            val buffer = ByteBufferList()
            // stream the data and digest it
            while (connected.read(buffer)) {
                val byteArray = buffer.readBytes()
                received += byteArray.size
                clientDigest.update(byteArray)
            }
        }

        Http2Connection(pair.second, false)
        .acceptHttpAsync {
            StatusCode.OK(body = BinaryBody(read = body))
        }
        .processMessagesAsync()

        val clientMd5 = clientDigest.digest()
        val serverMd5 = serverDigest.digest()

        assertEquals(sent, received)
        assertEquals(sent, 100000000)

        assertEquals(clientMd5.joinToString { it.toString(16) }, serverMd5.joinToString { it.toString(16) })
    }

    // this will test the connection flow control.
    // default write window is 16mb.
    @Test
    fun testBigRequest() {
        val pair = createAsyncPipeSocketPair()

        val random = Random.Default
        var sent = 0
        // 100 mb body
        val serverDigest = CrappyDigest.getInstance()
        val clientDigest = CrappyDigest.getInstance()
        // generate ~100mb of random data and digest it.
        val packetSize = 10000
        val body: AsyncRead = AsyncReader {
                it.putAllocatedBytes(packetSize) { bytes, bytesOffset ->
                    random.nextBytes(bytes, bytesOffset, bytesOffset + packetSize)
                    sent += packetSize
                    serverDigest.update(bytes, bytesOffset, bytesOffset + packetSize)
                }
                true
            }.pipe(createContentLengthPipe(100000000))

        var received = 0
        Http2Connection(pair.second, false)
        .acceptHttpAsync {
            val buffer = ByteBufferList()
            // stream the data and digest it
            while (request.body!!(buffer)) {
                val byteArray = buffer.readBytes()
                received += byteArray.size
                clientDigest.update(byteArray)
            }

            StatusCode.OK(body = Utf8StringBody("hello world"))
        }
        .processMessagesAsync()

        async {
            val client = Http2Connection(pair.first, true)
            client.processMessagesAsync()
            val connected =
                client.connect(Methods.POST("https://example.com/", body = BinaryBody(read = body)))
            val data = readAllString({connected.read(it)})
            assertEquals(data, "hello world")
        }

        val clientMd5 = clientDigest.digest()
        val serverMd5 = serverDigest.digest()

        assertEquals(sent, received)
        assertEquals(sent, 100000000)

        assertEquals(clientMd5.joinToString { it.toString(16) }, serverMd5.joinToString { it.toString(16) })
    }

//    @Test
    fun testCancelledRequests() {
        val pair = createAsyncPipeSocketPair()

        Http2Connection(pair.second, false)
                .acceptHttpAsync {
                    StatusCode.OK(body = Utf8StringBody("hello world"))
                }
                .processMessagesAsync()

        var completed = 0
        async {
            val client = Http2Connection(pair.first, true)
            client.processMessagesAsync()

            for (i in 0 until 10) {
                val connected =
                        client.connect(Methods.POST("https://example.com/", body = BinaryBody(createUnboundRandomRead()::read)))
                val data = readAllString({connected.read(it)})
                assertEquals(data, "hello world")
                completed++
            }
        }

        assertEquals(completed, 10)
    }
}
