package com.koushikdutta.scratch

import buildUpon
import com.koushikdutta.scratch.TestUtils.Companion.createRandomRead
import com.koushikdutta.scratch.TestUtils.Companion.createUnboundRandomRead
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.http.*
import com.koushikdutta.scratch.http.body.BinaryBody
import com.koushikdutta.scratch.http.body.Utf8StringBody
import com.koushikdutta.scratch.http.client.*
import com.koushikdutta.scratch.http.client.middleware.AsyncHttpClientMiddleware
import com.koushikdutta.scratch.http.client.middleware.createContentLengthPipe
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import com.koushikdutta.scratch.parser.readAllString
import com.koushikdutta.scratch.uri.URI
import execute
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.koushikdutta.scratch.http.http2.Http2Connection


class HttpTests {
    @Test
    fun testHttp() {
        val pair = createAsyncPipeSocketPair()

        async {
            val httpServer = AsyncHttpServer {
                StatusCode.OK(body = Utf8StringBody("hello world"))
            }

            httpServer.accept(pair.second)
        }


        var requestsCompleted = 0
        async {
            val httpClient = AsyncHttpClient()
            val reader = AsyncReader({ pair.first.read(it) })

            for (i in 1..3) {
                val data = httpClient.execute(Methods.GET("http://example/foo"), pair.first, reader) { readAllString(it.body!!) }
                assertEquals(data, "hello world")
                requestsCompleted++
            }
        }

        assertEquals(requestsCompleted, 3)
    }

    @Test
    fun testHttpEcho() {
        val pair = createAsyncPipeSocketPair()

        async {
            val httpServer = AsyncHttpServer {
                // would be cool to pipe hte request right back to the response
                // without buffering, but the http spec does not work that way.
                // entire request must be received before sending a response.
                val data = readAllString(request.body!!)
                assertEquals(data, "hello world")
                StatusCode.OK(body = Utf8StringBody(data))
            }

            httpServer.accept(pair.second)
        }

        var requestsCompleted = 0
        async {
            val httpClient = AsyncHttpClient()
            val reader = AsyncReader({ pair.first.read(it) })

            for (i in 1..3) {
                val request =
                    AsyncHttpRequest(URI.create("http://example/foo"), "POST", body = Utf8StringBody("hello world"))
                val data = httpClient.execute(request, pair.first, reader) { readAllString(it.body!!) }
                assertEquals(data, "hello world")
                requestsCompleted++
            }
        }

        assertEquals(requestsCompleted, 3)
    }

    @Test
    fun testHttpPipeServer() {
        val server = createAsyncPipeServerSocket()
        val httpServer = AsyncHttpServer {
            // would be cool to pipe hte request right back to the response
            // without buffering, but the http spec does not work that way.
            // entire request must be received before sending a response.
            val data = readAllString(request.body!!)
            assertEquals(data, "hello world")
            StatusCode.OK(body = Utf8StringBody(data))
        }

        httpServer.listen(server)

        var requestsCompleted = 0
        async {
            val httpClient = AsyncHttpClient()
            val socket = server.connect()
            val reader = AsyncReader({ socket.read(it) })

            for (i in 1..3) {
                val request =
                    AsyncHttpRequest(URI.create("http://example/foo"), "POST", body = Utf8StringBody("hello world"))
                val data = httpClient.execute(request, socket, reader) { readAllString(it.body!!) }
                assertEquals(data, "hello world")
                requestsCompleted++
            }
        }

        assertEquals(requestsCompleted, 3)
    }

    @Test
    fun testBigResponse() {
        val random = Random.Default
        var sent = 0
        val serverDigest = CrappyDigest.getInstance()
        val packetSize = 10000
        val body: AsyncRead = AsyncReader {
            it.putAllocatedBytes(packetSize) { bytes, bytesOffset ->
                random.nextBytes(bytes, bytesOffset, bytesOffset + packetSize)
                sent += packetSize
                serverDigest.update(bytes, bytesOffset, bytesOffset + packetSize)
            }
            true
        }.pipe(createContentLengthPipe(100000000))

        val server = createAsyncPipeServerSocket()
        val httpServer = AsyncHttpServer {
            StatusCode.OK(body = BinaryBody(read = body))
        }

        httpServer.listen(server)

        val clientDigest = CrappyDigest.getInstance()
        var received = 0
        async {
            val client = AsyncHttpClient()
            val socket = server.connect()
            val reader = AsyncReader({ socket.read(it) })
            val buffer = ByteBufferList()
            client.execute(Methods.GET("https://example.com/"), socket, reader) {
                // stream the data and digest it
                while (it.body!!(buffer)) {
                    val byteArray = buffer.readBytes()
                    received += byteArray.size
                    clientDigest.update(byteArray)
                }
            }
        }

        val clientMd5 = clientDigest.digest()
        val serverMd5 = serverDigest.digest()

        assertEquals(sent, received)
        assertEquals(sent, 100000000)

        assertEquals(clientMd5.joinToString { it.toString(16) }, serverMd5.joinToString { it.toString(16) })
    }

    @Test
    fun testBigRequest() {
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
        val server = createAsyncPipeServerSocket()
        val httpServer = AsyncHttpServer {
            val buffer = ByteBufferList()
            // stream the data and digest it
            while (request.body!!(buffer)) {
                val byteArray = buffer.readBytes()
                received += byteArray.size
                clientDigest.update(byteArray)
            }

            StatusCode.OK(body = Utf8StringBody("hello world"))
        }
        httpServer.listen(server)

        async {
            val client = AsyncHttpClient()
            val socket = server.connect()
            val reader = AsyncReader({ socket.read(it) })
            val data = client.execute(
                Methods.POST("https://example.com/", body = BinaryBody(read = body)),
                socket,
                reader
            ) {
                readAllString(it.body!!)
            }
            assertEquals(data, "hello world")
        }

        val clientMd5 = clientDigest.digest()
        val serverMd5 = serverDigest.digest()

        assertEquals(sent, received)
        assertEquals(sent, 100000000)

        assertEquals(clientMd5.joinToString { it.toString(16) }, serverMd5.joinToString { it.toString(16) })
    }

    @Test
    fun testHttp2ServerPriorKnowledge() {
        val server = createAsyncPipeServerSocket()

        val httpServer = AsyncHttpServer {
            // would be cool to pipe hte request right back to the response
            // without buffering, but the http spec does not work that way.
            // entire request must be received before sending a response.
            val data = readAllString(request.body!!)
            assertEquals(data, "hello world")
            StatusCode.OK(body = Utf8StringBody(data))
        }

        httpServer.listen(server)

        var data = ""
        async {
            val connection = Http2Connection(server.connect(), true)
            connection.processMessagesAsync()
            val stream =
                connection.connect(
                    Methods.POST(
                        "https://example.com/",
                        body = Utf8StringBody("hello world")
                    )
                )
            data = readAllString(stream::read)
        }

        assertEquals(data, "hello world")
    }

    @Test
    fun testHttpPipeServerALot() {
        val postLength = 1000000
        val server = createAsyncPipeServerSocket()
        val httpServer = AsyncHttpServer {
            // would be cool to pipe hte request right back to the response
            // without buffering, but the http spec does not work that way.
            // entire request must be received before sending a response.
            var len = 0
            val buf = ByteBufferList()
            while (request.body!!(buf)) {
                len += buf.remaining()
                buf.free()
            }
            assertEquals(postLength, len)
            StatusCode.OK(body = Utf8StringBody("hello world"))
        }

        httpServer.listen(server)

        var requestsCompleted = 0
        val httpClient = AsyncHttpClient()
        val random = Random.Default

        for (i in 1..10000) {
            async {
                val socket = server.connect()
                val reader = AsyncReader({ socket.read(it) })

                val request =
                    AsyncHttpRequest(URI.create("http://example/foo"), "POST", body = createRandomRead(postLength))
                val data = httpClient.execute(request, socket, reader) { readAllString(it.body!!) }
                assertEquals(data, "hello world")
                requestsCompleted++
            }
        }

        assertEquals(requestsCompleted, 10000)
    }

    @Test
    fun testHttpRequestSent() {
        val pair = createAsyncPipeSocketPair()

        var responseSent = 0
        async {
            val httpServer = AsyncHttpServer {
                StatusCode.OK(body = Utf8StringBody("hello world")) {
                    responseSent++
                }
            }

            httpServer.accept(pair.second)
        }

        var requestsCompleted = 0
        var requestSent = 0
        async {
            val httpClient = AsyncHttpClient()
            val reader = AsyncReader({ pair.first.read(it) })

            val get = Methods.GET("http://example/foo") {
                requestSent++
            }
            val data = httpClient.execute(get, pair.first, reader) { readAllString(it.body!!) }
            assertEquals(data, "hello world")
            requestsCompleted++
        }

        assertEquals(requestsCompleted, 1)
        assertEquals(requestSent, 1)
        assertEquals(responseSent, 1)
    }

    @Test
    fun testHttpRedirect() {
        val pipeServer = createAsyncPipeServerSocket()
        async {
            val httpServer = AsyncHttpServer {
                if (request.uri.path == "/redirect")
                    StatusCode.movedPermanently("/")
                else
                    StatusCode.OK(body = Utf8StringBody("hello world"))
            }

            httpServer.listen(pipeServer)
        }

        var data = ""
        async {
            val httpClient = AsyncHttpClient().buildUpon().followRedirects().build()
            httpClient.client.middlewares.add(0, object : AsyncHttpClientMiddleware() {
                override suspend fun connectSocket(session: AsyncHttpClientSession): Boolean {
                    session.transport = AsyncHttpClientTransport(pipeServer.connect())
                    return true
                }
            })
            val get = Methods.GET("http://example/redirect")
            data = httpClient.execute(get) { readAllString(it.body!!) }
        }
        assertEquals(data, "hello world")
    }

    @Test
    fun testResponseTermination() {
        var gotClose = false
        val pipeServer = createAsyncPipeServerSocket()
        async {
            val httpServer = AsyncHttpServer {
                StatusCode.OK(body = BinaryBody(createUnboundRandomRead()::read)) {
                    gotClose = it != null
                }
            }

            httpServer.listen(pipeServer)
        }

        async {
            val httpClient = AsyncHttpClient().buildUpon().followRedirects().build()
            httpClient.client.middlewares.add(0, object : AsyncHttpClientMiddleware() {
                override suspend fun connectSocket(session: AsyncHttpClientSession): Boolean {
                    session.transport = AsyncHttpClientTransport(pipeServer.connect())
                    return true
                }
            })
            val get = Methods.GET("http://example/")
            httpClient.execute(get) {
                // do nothing with the data.
            }
        }

        assertTrue(gotClose)
    }

    @Test
    fun testConnectionUpgrade() {
        var protocolSwitched = false
        val pipeServer = createAsyncPipeServerSocket()
        val httpServer = AsyncHttpServer {
            AsyncHttpResponse.SWITCHING_PROTOCOLS {
                protocolSwitched = true
            }
        }
        httpServer.listen(pipeServer)

        async {
            val httpClient = AsyncHttpClient()
            val clientSocket = pipeServer.connect()
            val headers = Headers()
            headers["Connection"] = "Upgrade"
            headers["Upgrade"] = "TestProtocol"
            val request = Methods.GET("http://example.org", headers)
            try {
                httpClient.execute(request, clientSocket)
            }
            catch (switching: AsyncHttpClientSwitchingProtocols) {
                return@async
            }
            throw Exception("Expected client to switch protocols")
        }
        assertTrue(protocolSwitched)
    }
}
