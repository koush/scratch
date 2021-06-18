package com.koushikdutta.scratch

import com.koushikdutta.scratch.TestUtils.Companion.countBytes
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.StatusCode
import com.koushikdutta.scratch.http.body.Utf8StringBody
import com.koushikdutta.scratch.http.client.execute
import com.koushikdutta.scratch.http.client.executor.AsyncHttpConnectSocketExecutor
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import com.koushikdutta.scratch.parser.*
import com.koushikdutta.scratch.tls.*
import com.koushikdutta.scratch.uri.URI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail


class TlsTests {
    @Test
    fun testCertificate() {
        val keypairCert = createSelfSignedCertificate("TestServer")

        val pair = createAsyncPipeSocketPair()

        val a = async {
            val serverContext = createTLSContext()
            serverContext.init(keypairCert.first, keypairCert.second)

            val engine = serverContext.createSSLEngine()
            engine.useClientMode = false

            val server = tlsHandshake(pair.first, engine)
            server.write(ByteBufferList().putUtf8String("Hello World"))
            server.close()
        }

        var data = ""
        val b = async {
            val clientContext = createTLSContext()
            clientContext.init(keypairCert.second)

            val engine = clientContext.createSSLEngine("TestServer", 80)
            engine.useClientMode = true

            val client = tlsHandshake(pair.second, engine)
            data = client.parse().readString()
        }

        assertEquals(data, "Hello World")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testCertificateNameMismatch() {
        val keypairCert = createSelfSignedCertificate("TestServer")

        val pair = createAsyncPipeSocketPair()

        try {
            async {
                val serverContext = createTLSContext()
                serverContext.init(keypairCert.first, keypairCert.second)

                val engine = serverContext.createSSLEngine()
                engine.useClientMode = false

                val server = tlsHandshake(pair.first, engine)
                server.write(ByteBufferList().putUtf8String("Hello World"))
                server.close()
            }

            val result2 = async {
                val clientContext = createTLSContext()
                clientContext.init(keypairCert.second)

                val engine = clientContext.createSSLEngine("BadServerName", 80)
                engine.useClientMode = true

                val client = tlsHandshake(pair.second, engine)
                client.parse().readString()
            }

            result2.getCompleted()
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

            val result2 = async {

                val engine = getDefaultSSLContext().createSSLEngine("BadServerName", 80)
                engine.useClientMode = true

                val client = tlsHandshake(pair.second, engine)
                client.parse().readString()
            }

            result2.getCompleted()
            result1.getCompleted()
        } catch (exception: SSLException) {
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
        tlsServer.acceptAsync {
            data += this.parse().readString()
        }

        for (i in 1..2) {
            launch {
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

        tlsServer.acceptAsync {
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

                var got = server.connect().connectTls("TestServer", 80, clientContext).countBytes()
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
        tlsServer.acceptAsync {
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
        launch {
            val clientContext = createTLSContext()
            clientContext.init(keypairCert.second)
            count += server.connect().connectTls("TestServer", 80, clientContext).countBytes()
        }

        assertEquals(count, dataLen)
        // check handshake allocations
        // these are big due to certs. oof.
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
        tlsServer.acceptAsync {
            data += this.parse().readString()
        }

        launch {
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
            val data = it.body!!.parse().readString()
            assertEquals(data, "hello world")
            StatusCode.OK(body = Utf8StringBody(data))
        }

        httpServer.listenAsync(tlsServer)

        var requestsCompleted = 0
        launch {
            val clientContext = createTLSContext()
            clientContext.init(keypairCert.second)

            val httpClient = AsyncHttpConnectSocketExecutor {
                server.connect().connectTls("TestServer", 80, clientContext)
            }

            for (i in 1..3) {
                val request =
                    AsyncHttpRequest(URI("http://example/foo"), "POST", body = Utf8StringBody("hello world"))
                val data = httpClient.execute(request) { it.body!!.parse().readString() }
                assertEquals(data, "hello world")
                requestsCompleted++
            }
        }

        assertEquals(requestsCompleted, 3)
    }
}