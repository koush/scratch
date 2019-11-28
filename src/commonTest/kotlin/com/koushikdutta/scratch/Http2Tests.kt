package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.http.*
import com.koushikdutta.scratch.http.body.BinaryBody
import com.koushikdutta.scratch.http.body.Utf8StringBody
import com.koushikdutta.scratch.http.client.middleware.createContentLengthPipe
import com.koushikdutta.scratch.http.http2.Http2Connection
import com.koushikdutta.scratch.http.http2.arraycopy
import com.koushikdutta.scratch.http.http2.bitCount
import com.koushikdutta.scratch.parser.readAllString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class Http2Tests {
    @Test
    fun testBitcount() {
        assertEquals(0xFF.bitCount(), 8)
        assertEquals(0xFF00.bitCount(), 8)
        assertEquals(0xFF0000.bitCount(), 8)
        assertEquals((0xFF000000).toInt().bitCount(), 8)
        assertEquals((0xFF000001).toInt().bitCount(), 9)
    }

    @Test
    fun testArrayCopy() {
        val array = arrayOf(1,2,3,4)
        val array2 = Array<Int>(2, {0})
        array.arraycopy(2, array2, 1, 1)
        assertEquals(array2[1], 3)
    }

    @Test
    fun testConnection() {
        val pair = createAsyncPipeSocketPair()

        async {
            val server = Http2Connection(pair.second, false) {
                AsyncHttpResponse.OK(body = Utf8StringBody("Hello World"))
            }
        }

        var data = ""
        async {
            val client = Http2Connection(pair.first, true)
            val connected = client.newStream(AsyncHttpRequest.GET("https://example.com/"))
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
            val connected = client.newStream(AsyncHttpRequest.GET("https://example.com/"))
            val buffer = ByteBufferList()
            // stream the data and digest it
            while (connected.read(buffer)) {
                val byteArray = buffer.readBytes()
                received += byteArray.size
                clientDigest.update(byteArray)
            }
        }

        Http2Connection(pair.second, false) {
            AsyncHttpResponse.OK(body = BinaryBody(body = body))
        }

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
        Http2Connection(pair.second, false) {
            val buffer = ByteBufferList()
            // stream the data and digest it
            while (it.body!!(buffer)) {
                val byteArray = buffer.readBytes()
                received += byteArray.size
                clientDigest.update(byteArray)
            }

            AsyncHttpResponse.OK(body = Utf8StringBody("hello world"))
        }

        async {
            val client = Http2Connection(pair.first, true)
            val connected =
                client.newStream(AsyncHttpRequest.POST("https://example.com/", body = BinaryBody(body = body)))
            val data = readAllString({connected.read(it)})
            assertEquals(data, "hello world")
        }

        val clientMd5 = clientDigest.digest()
        val serverMd5 = serverDigest.digest()

        assertEquals(sent, received)
        assertEquals(sent, 100000000)

        assertEquals(clientMd5.joinToString { it.toString(16) }, serverMd5.joinToString { it.toString(16) })
    }
}
