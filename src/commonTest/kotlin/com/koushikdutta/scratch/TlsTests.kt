package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.OK
import com.koushikdutta.scratch.http.body.Utf8StringBody
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import com.koushikdutta.scratch.parser.readAllString
import com.koushikdutta.scratch.tls.*
import com.koushikdutta.scratch.uri.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import com.koushikdutta.scratch.TestUtils.Companion.count


class TlsTests {
    @Test
    fun testCertificate() {
        val keypairCert = createSelfSignedCertificate("TestServer")

        val pair = createAsyncPipeSocketPair()


        async {
            val serverContext = createTLSContext()
            serverContext.init(keypairCert.first, keypairCert.second)

            val engine = serverContext.createSSLEngine()
            engine.useClientMode = false

            val server = tlsHandshake(pair.first, engine)
            server.write(ByteBufferList().putUtf8String("Hello World"))
            server.close()
        }

        var data = ""
        async {
            val clientContext = createTLSContext()
            clientContext.init(keypairCert.second)

            val engine = clientContext.createSSLEngine("TestServer", 80)
            engine.useClientMode = true

            val client = tlsHandshake(pair.second, engine)
            data = readAllString({client.read(it)})
        }

        assertEquals(data, "Hello World")
    }

    @Test
    fun testCertificateNameMismatch() {
        val keypairCert = createSelfSignedCertificate("TestServer")

        val pair = createAsyncPipeSocketPair()

        try {
            val result1 = async {
                val serverContext = createTLSContext()
                serverContext.init(keypairCert.first, keypairCert.second)

                val engine = serverContext.createSSLEngine()
                engine.useClientMode = false

                val server = tlsHandshake(pair.first, engine)
                server.write(ByteBufferList().putUtf8String("Hello World"))
                server.close()
            }

            var data = ""
            val result2 = async {
                val clientContext = createTLSContext()
                clientContext.init(keypairCert.second)

                val engine = clientContext.createSSLEngine("BadServerName", 80)
                engine.useClientMode = true

                val client = tlsHandshake(pair.second, engine)
                data = readAllString({client.read(it)})
            }

            result1.rethrow()
            result2.rethrow()
        } catch (exception: SSLException) {
            assertTrue(exception.message!!.toLowerCase().contains("hostname"))
            return
        }
        fail("exception expected")
    }


    @Test
    fun testCertificateTrustFailure() {
        val keypairCert = createSelfSignedCertificate("TestServer")

        val pair = createAsyncPipeSocketPair()

        try {
            val result1 = async {
                val serverContext = createTLSContext()
                serverContext.init(keypairCert.first, keypairCert.second)

                val engine = serverContext.createSSLEngine()
                engine.useClientMode = false

                val server = tlsHandshake(pair.first, engine)
                server.write(ByteBufferList().putUtf8String("Hello World"))
                server.close()
            }

            var data = ""
            val result2 = async {

                val engine = getDefaultSSLContext().createSSLEngine("BadServerName", 80)
                engine.useClientMode = true

                val client = tlsHandshake(pair.second, engine)
                data = readAllString({client.read(it)})
            }

            result1.rethrow()
            result2.rethrow()
        } catch (exception: SSLHandshakeException) {
            return
        }
        fail("exception expected")
    }

    @Test
    fun testTlsServer() {
        val keypairCert = createSelfSignedCertificate("TestServer")
        val serverContext = createTLSContext()
        serverContext.init(keypairCert.first, keypairCert.second)

        val server = createAsyncPipeServerSocket()
        val tlsServer = server.listenTls(serverContext)


        var data = ""
        tlsServer.accept().receive {
            data += readAllString({read(it)})
        }

        for (i in 1..2) {
            async {
                val clientContext = createTLSContext()
                clientContext.init(keypairCert.second)

                val client = server.connect().connectTls("TestServer", 80, clientContext)
                client.write(ByteBufferList().putUtf8String("hello world"))
                client.close()
            }
        }

        assertEquals(data, "hello worldhello world")
    }

    @Test
    fun testTlsServerALot() {
        val keypairCert = createSelfSignedCertificate("TestServer")
        val serverContext = createTLSContext()
        serverContext.init(keypairCert.first, keypairCert.second)

        val server = createAsyncPipeServerSocket()
        val tlsServer = server.listenTls(serverContext)

        tlsServer.accept().receive {
            val buffer = ByteBufferList()
            val random = TestUtils.createRandomRead(5000000)
            var written = 0
            while (random(buffer)) {
                written += buffer.remaining()
                write(buffer)
                assertTrue(buffer.isEmpty)
            }
            assertEquals(written, 5000000)
            close()
        }

        var count = 0
        for (i in 1..10) {
            async {
                val clientContext = createTLSContext()
                clientContext.init(keypairCert.second)

                var got = server.connect().connectTls("TestServer", 80, clientContext)::read.count()
                assertEquals(got, 5000000)
                count += got
            }
        }

        assertEquals(count, 5000000 * 10)
    }

    @Test
    fun testByteBufferAllocations() {
        val start = ByteBufferList.totalObtained
        val keypairCert = createSelfSignedCertificate("TestServer")
        val serverContext = createTLSContext()
        serverContext.init(keypairCert.first, keypairCert.second)
        val server = createAsyncPipeServerSocket()
        val tlsServer = server.listenTls(serverContext)

        // 100mb
        val dataLen = 100000000
        var mid = 0L
        tlsServer.accept().receive {
            mid = ByteBufferList.totalObtained
            val buffer = ByteBufferList()
            val random = TestUtils.createRandomRead(dataLen)
            while (random(buffer)) {
                write(buffer)
                assertTrue(buffer.isEmpty)
            }
            close()
        }

        var count = 0
        async {
            val clientContext = createTLSContext()
            clientContext.init(keypairCert.second)
            count += server.connect().connectTls("TestServer", 80, clientContext)::read.count()
        }

        assertEquals(count, dataLen)
        // check handshake allocations
        assertTrue(mid - start < 250000)
        // check streaming allocations
        assertTrue(ByteBufferList.totalObtained - mid < 80000)
    }

    @Test
    fun testTlsServer2() {
        val keypairCert = createSelfSignedCertificate("TestServer")
        val serverContext = createTLSContext()
        serverContext.init(keypairCert.first, keypairCert.second)

        val server = createAsyncPipeServerSocket()
        val tlsServer = server.listenTls(serverContext)


        var data = ""
        tlsServer.accept().receive {
            data += readAllString({read(it)})
        }

        async {
            for (i in 1..2) {
                val clientContext = createTLSContext()
                clientContext.init(keypairCert.second)

                val client = server.connect().connectTls("TestServer", 80, clientContext)
                client.write(ByteBufferList().putUtf8String("hello world"))
                client.close()
            }
        }

        assertEquals(data, "hello worldhello world")
    }

    @Test
    fun testHttpsPipeServer() {
        val keypairCert = createSelfSignedCertificate("TestServer")
        val serverContext = createTLSContext()
        serverContext.init(keypairCert.first, keypairCert.second)

        val server = createAsyncPipeServerSocket()
        val tlsServer = server.listenTls(serverContext)
        val httpServer = AsyncHttpServer {
            // would be cool to pipe hte request right back to the response
            // without buffering, but the http spec does not work that way.
            // entire request must be received before sending a response.
            val data = readAllString(it.body!!)
            assertEquals(data, "hello world")
            AsyncHttpResponse.OK(body = Utf8StringBody(data))
        }

        httpServer.listen(tlsServer)

        var requestsCompleted = 0
        async {
            val clientContext = createTLSContext()
            clientContext.init(keypairCert.second)

            val httpClient = AsyncHttpClient()
            val socket = server.connect().connectTls("TestServer", 80, clientContext)
            val reader = AsyncReader({socket.read(it)})

            for (i in 1..3) {
                val request =
                    AsyncHttpRequest(URI.create("http://example/foo"), "POST", body = Utf8StringBody("hello world"))
                val result = httpClient.execute(request, socket, reader)
                val data = readAllString(result.body!!)
                assertEquals(data, "hello world")
                requestsCompleted++
            }
        }

        assertEquals(requestsCompleted, 3)
    }
}