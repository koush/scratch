package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.http.*
import com.koushikdutta.scratch.http.body.BinaryBody
import com.koushikdutta.scratch.http.body.StringBody
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.client.middleware.createContentLengthPipe
import com.koushikdutta.scratch.http.http2.Http2Connection
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import com.koushikdutta.scratch.parser.readAllString
import com.koushikdutta.scratch.tls.connectTls
import com.koushikdutta.scratch.tls.createSelfSignedCertificate
import com.koushikdutta.scratch.tls.initializeSSLContext
import com.koushikdutta.scratch.tls.listenTls
import org.junit.Test
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.SSLContext


class HttpTests {
    @Test
    fun testHttp() {
        val pair = createAsyncPipeSocketPair()

        async {
            val httpServer = AsyncHttpServer {
                AsyncHttpResponse.OK(body = StringBody("hello world"))
            }

            httpServer.accept(pair.second)
        }


        var requestsCompleted = 0
        async {
            val httpClient = AsyncHttpClient()
            val reader = AsyncReader(pair.first::read)

            for (i in 1..3) {
                val result = httpClient.execute(AsyncHttpRequest.GET("http://example/foo"), pair.first, reader)
                val data = readAllString(result.body!!)
                assert(data == "hello world")
                requestsCompleted++
            }
        }

        assert(requestsCompleted == 3)
    }

    @Test
    fun testHttpEcho() {
        val pair = createAsyncPipeSocketPair()

        async {
            val httpServer = AsyncHttpServer {
                // would be cool to pipe hte request right back to the response
                // without buffering, but the http spec does not work that way.
                // entire request must be received before sending a response.
                val data = readAllString(it.body!!)
                assert(data == "hello world")
                AsyncHttpResponse.OK(body = StringBody(data))
            }

            httpServer.accept(pair.second)
        }

        var requestsCompleted = 0
        async {
            val httpClient = AsyncHttpClient()
            val reader = AsyncReader(pair.first::read)

            for (i in 1..3) {
                val request = AsyncHttpRequest(URI.create("http://example/foo"), "POST", body = StringBody("hello world"))
                val result = httpClient.execute(request, pair.first, reader)
                val data = readAllString(result.body!!)
                assert(data == "hello world")
                requestsCompleted++
            }
        }

        assert(requestsCompleted == 3)
    }


    @Test
    fun testHttpPipeServer() {
        val server = createAsyncPipeServerSocket()
        val httpServer = AsyncHttpServer {
            // would be cool to pipe hte request right back to the response
            // without buffering, but the http spec does not work that way.
            // entire request must be received before sending a response.
            val data = readAllString(it.body!!)
            assert(data == "hello world")
            AsyncHttpResponse.OK(body = StringBody(data))
        }

        httpServer.listen(server)

        var requestsCompleted = 0
        async {
            val httpClient = AsyncHttpClient()
            val socket = server.connect()
            val reader = AsyncReader(socket::read)

            for (i in 1..3) {
                val request = AsyncHttpRequest(URI.create("http://example/foo"), "POST", body = StringBody("hello world"))
                val result = httpClient.execute(request, socket, reader)
                val data = readAllString(result.body!!)
                assert(data == "hello world")
                requestsCompleted++
            }
        }

        assert(requestsCompleted == 3)
    }


    @Test
    fun testHttpsPipeServer() {
        val keypairCert = createSelfSignedCertificate("TestServer")
        val serverContext = SSLContext.getInstance("TLS")
        initializeSSLContext(serverContext, keypairCert.first, keypairCert.second)

        val server = createAsyncPipeServerSocket()
        val tlsServer = server.listenTls(serverContext)
        val httpServer = AsyncHttpServer {
            // would be cool to pipe hte request right back to the response
            // without buffering, but the http spec does not work that way.
            // entire request must be received before sending a response.
            val data = readAllString(it.body!!)
            assert(data == "hello world")
            AsyncHttpResponse.OK(body = StringBody(data))
        }

        httpServer.listen(tlsServer)

        var requestsCompleted = 0
        async {
            val clientContext = SSLContext.getInstance("TLS")
            initializeSSLContext(clientContext, keypairCert.second)

            val httpClient = AsyncHttpClient()
            val socket = server.connect().connectTls("TestServer", 80, clientContext)
            val reader = AsyncReader(socket::read)

            for (i in 1..3) {
                val request = AsyncHttpRequest(URI.create("http://example/foo"), "POST", body = StringBody("hello world"))
                val result = httpClient.execute(request, socket, reader)
                val data = readAllString(result.body!!)
                assert(data == "hello world")
                requestsCompleted++
            }
        }

        assert(requestsCompleted == 3)
    }

    @Test
    fun testBigResponse() {
        val random = SecureRandom()
        var sent = 0
        val serverDigest = MessageDigest.getInstance("MD5")
        val body: AsyncRead = createContentLengthPipe(100000000, AsyncReader {
            val buffer = ByteBuffer.allocate(10000)
            random.nextBytes(buffer.array())
            sent += buffer.remaining()
            serverDigest.update(buffer.array())

            it.add(buffer)
            true
        })

        val server = createAsyncPipeServerSocket()
        val httpServer = AsyncHttpServer {
            AsyncHttpResponse.OK(body = BinaryBody(read = body))
        }

        httpServer.listen(server)

        val clientDigest = MessageDigest.getInstance("MD5")
        var received = 0
        async {
            val client = AsyncHttpClient()
            val socket = server.connect()
            val reader = AsyncReader(socket::read)
            val connected = client.execute(AsyncHttpRequest.GET("https://example.com/"), socket, reader)
            val buffer = ByteBufferList()
            // stream the data and digest it
            while (connected.body!!(buffer)) {
                val byteArray = buffer.bytes
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


    @Test
    fun testHttp2ServerPriorKnowledge() {
        val server = createAsyncPipeServerSocket()

        val httpServer = AsyncHttpServer {
            // would be cool to pipe hte request right back to the response
            // without buffering, but the http spec does not work that way.
            // entire request must be received before sending a response.
            val data = readAllString(it.body!!)
            assert(data == "hello world")
            AsyncHttpResponse.OK(body = StringBody(data))
        }

        httpServer.listen(server)

        var data = ""
        async {
            val connection = Http2Connection(server.connect(), true)
            val stream = connection.newStream(AsyncHttpRequest.POST("https://example.com/", body = StringBody("hello world")), false)
            data = readAllString(stream::read)
        }

        assert(data == "hello world")
    }
}
