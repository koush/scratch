package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.http.*
import com.koushikdutta.scratch.http.body.BinaryBody
import com.koushikdutta.scratch.http.body.StringBody
import com.koushikdutta.scratch.http.client.middleware.createContentLengthPipe
import com.koushikdutta.scratch.http.http2.Http2Connection
import com.koushikdutta.scratch.parser.readAllString
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.random.Random

class Http2Tests {
    @Test
    fun testConnection() {
        val pair = createAsyncPipeSocketPair()

        async {
            val server = Http2Connection(pair.second, false) {
                AsyncHttpResponse.OK(body = StringBody("Hello World"))
            }
        }

        var data = ""
        async {
            val client = Http2Connection(pair.first, true)
            val connected = client.newStream(AsyncHttpRequest.GET("https://example.com/"))
            data = readAllString(connected::read)
        }

        assert(data == "Hello World")
    }


    // this will test the connection flow control.
    // default write window is 16mb.
    @Test
    fun testBigResponse() {
        val pair = createAsyncPipeSocketPair()

        val random = SecureRandom()
        var sent = 0
        // 100 mb body
        val serverDigest = MessageDigest.getInstance("MD5")
        // generate ~100mb of random data and digest it.
        val body: AsyncRead = createContentLengthPipe(100000000, AsyncReader {
            val buffer = ByteBuffer.allocate(10000)
            random.nextBytes(buffer.array())
            sent += buffer.remaining()
            serverDigest.update(buffer.array())

            it.add(buffer)
            true
        })

        Http2Connection(pair.second, false) {
            AsyncHttpResponse.OK(body = BinaryBody(read = body))
        }

        val clientDigest = MessageDigest.getInstance("MD5")
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

        val clientMd5 = clientDigest.digest()
        val serverMd5 = serverDigest.digest()

        assert(sent == received)
        assert(sent == 100000000)

        assert(clientMd5.joinToString { it.toString(16) } == serverMd5.joinToString { it.toString(16) })
    }


    // this will test the connection flow control.
    // default write window is 16mb.
    @Test
    fun testBigRequest() {
        val pair = createAsyncPipeSocketPair()

        val random = SecureRandom()
        var sent = 0
        // 100 mb body
        val serverDigest = MessageDigest.getInstance("MD5")
        val clientDigest = MessageDigest.getInstance("MD5")
        // generate ~100mb of random data and digest it.
        val body: AsyncRead = createContentLengthPipe(100000000, AsyncReader {
            val buffer = ByteBuffer.allocate(10000)
            random.nextBytes(buffer.array())
            sent += buffer.remaining()
            serverDigest.update(buffer.array())

            it.add(buffer)
            true
        })

        var received = 0
        Http2Connection(pair.second, false) {
            val buffer = ByteBufferList()
            // stream the data and digest it
            while (it.body!!(buffer)) {
                val byteArray = buffer.readBytes()
                received += byteArray.size
                clientDigest.update(byteArray)
            }

            AsyncHttpResponse.OK(body = StringBody("hello world"))
        }

        async {
            val client = Http2Connection(pair.first, true)
            val connected = client.newStream(AsyncHttpRequest.POST("https://example.com/", body = BinaryBody(read = body)))
            val data = readAllString(connected::read)
            assert(data == "hello world")
        }

        val clientMd5 = clientDigest.digest()
        val serverMd5 = serverDigest.digest()

        assert(sent == received)
        assert(sent == 100000000)

        assert(clientMd5.joinToString { it.toString(16) } == serverMd5.joinToString { it.toString(16) })
    }
}
