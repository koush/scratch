package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.OK
import com.koushikdutta.scratch.http.POST
import com.koushikdutta.scratch.http.body.Utf8StringBody
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.client.AsyncHttpClientSession
import com.koushikdutta.scratch.http.client.middleware.ConscryptMiddleware
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import com.koushikdutta.scratch.parser.readAllString
import com.koushikdutta.scratch.tls.*
import com.koushikdutta.scratch.uri.URI
import org.conscrypt.Conscrypt
import org.junit.Test
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

class TlsTests {
    @Test
    fun testCertificate() {
        val keypairCert = createSelfSignedCertificate("TestServer")

        val pair = createAsyncPipeSocketPair()


        async {
            val serverContext = SSLContext.getInstance("TLS")
            initializeSSLContext(serverContext, keypairCert.first, keypairCert.second)

            val engine = serverContext.createSSLEngine()
            engine.useClientMode = false

            val server = tlsHandshake(pair.first, engine)
            server.write(ByteBufferList().putUtf8String("Hello World"))
            server.close()
        }

        var data = ""
        async {
            val clientContext = SSLContext.getInstance("TLS")
            initializeSSLContext(clientContext, keypairCert.second)

            val engine = clientContext.createSSLEngine("TestServer", 80)
            engine.useClientMode = true

            val client = tlsHandshake(pair.second, engine)
            data = readAllString(client::read)
        }

        assert(data == "Hello World")
    }

    @Test
    fun testCertificateNameMismatch() {
        val keypairCert = createSelfSignedCertificate("TestServer")

        val pair = createAsyncPipeSocketPair()

        try {
            val result1 = async {
                val serverContext = SSLContext.getInstance("TLS")
                initializeSSLContext(serverContext, keypairCert.first, keypairCert.second)

                val engine = serverContext.createSSLEngine()
                engine.useClientMode = false

                val server = tlsHandshake(pair.first, engine)
                server.write(ByteBufferList().putUtf8String("Hello World"))
                server.close()
            }

            var data = ""
            val result2 = async {
                val clientContext = SSLContext.getInstance("TLS")
                initializeSSLContext(clientContext, keypairCert.second)

                val engine = clientContext.createSSLEngine("BadServerName", 80)
                engine.useClientMode = true

                val client = tlsHandshake(pair.second, engine)
                data = readAllString(client::read)
            }

            result1.rethrow()
            result2.rethrow()
        }
        catch (exception: SSLException) {
            assert(exception.message!!.contains("hostname verification failed"))
            return
        }
        assert(false)
    }


    @Test
    fun testCertificateTrustFailure() {
        val keypairCert = createSelfSignedCertificate("TestServer")

        val pair = createAsyncPipeSocketPair()

        try {
            val result1 = async {
                val serverContext = SSLContext.getInstance("TLS")
                initializeSSLContext(serverContext, keypairCert.first, keypairCert.second)

                val engine = serverContext.createSSLEngine()
                engine.useClientMode = false

                val server = tlsHandshake(pair.first, engine)
                server.write(ByteBufferList().putUtf8String("Hello World"))
                server.close()
            }

            var data = ""
            val result2 = async {

                val engine = SSLContext.getDefault().createSSLEngine("BadServerName", 80)
                engine.useClientMode = true

                val client = tlsHandshake(pair.second, engine)
                data = readAllString(client::read)
            }

            result1.rethrow()
            result2.rethrow()
        }
        catch (exception: SSLHandshakeException) {
            return
        }
        assert(false)
    }

    @Test
    fun testTlsServer() {
        val keypairCert = createSelfSignedCertificate("TestServer")
        val serverContext = SSLContext.getInstance("TLS")
        initializeSSLContext(serverContext, keypairCert.first, keypairCert.second)

        val server = createAsyncPipeServerSocket()
        val tlsServer = server.listenTls(serverContext)


        var data = ""
        tlsServer.accept().receive {
            data += readAllString(::read)
        }

        for (i in 1..2) {
            async {
                val clientContext = SSLContext.getInstance("TLS")
                initializeSSLContext(clientContext, keypairCert.second)

                val client = server.connect().connectTls("TestServer", 80, clientContext)
                client.write(ByteBufferList().putUtf8String("hello world"))
                client.close()
            }
        }

        assert(data == "hello worldhello world")
    }

    @Test
    fun testTlsServer2() {
        val keypairCert = createSelfSignedCertificate("TestServer")
        val serverContext = SSLContext.getInstance("TLS")
        initializeSSLContext(serverContext, keypairCert.first, keypairCert.second)

        val server = createAsyncPipeServerSocket()
        val tlsServer = server.listenTls(serverContext)


        var data = ""
        tlsServer.accept().receive {
            data += readAllString(::read)
        }

        async {
            for (i in 1..2) {
                val clientContext = SSLContext.getInstance("TLS")
                initializeSSLContext(clientContext, keypairCert.second)

                val client = server.connect().connectTls("TestServer", 80, clientContext)
                client.write(ByteBufferList().putUtf8String("hello world"))
                client.close()
            }
        }

        assert(data == "hello worldhello world")
    }


    @Test
    fun testConscryptTlsServer() {
        val conscrypt = Conscrypt.newProvider()
//        Security.insertProviderAt(conscrypt, 1)

        val keypairCert = createSelfSignedCertificate("TestServer")
        val serverContext = SSLContext.getInstance("TLS", conscrypt)
        initializeSSLContext(serverContext, keypairCert.first, keypairCert.second)

        val server = createAsyncPipeServerSocket()
        val tlsServer = server.listenTls {
            serverContext.createSSLEngine("TestServer", 80)
        }


        var data = ""
        tlsServer.accept().receive {
            data += readAllString(::read)
        }

        for (i in 1..2) {
            async {
                val clientContext = SSLContext.getInstance("TLS", conscrypt)
                initializeSSLContext(clientContext, keypairCert.second)

                val client = server.connect().connectTls("TestServer", 80, clientContext)
                client.write(ByteBufferList().putUtf8String("hello world"))
                client.close()
            }
        }

        assert(data == "hello worldhello world")
    }

    @Test
    fun testHttp2Alpn() {
        val conscrypt = Conscrypt.newProvider()
        val keypairCert = createSelfSignedCertificate("TestServer")

        val serverContext = SSLContext.getInstance("TLS", conscrypt)
        initializeSSLContext(serverContext, keypairCert.first, keypairCert.second)


        val server = createAsyncPipeServerSocket()
        val tlsServer = server.listenTls {
            val engine = serverContext.createSSLEngine()
            Conscrypt.setApplicationProtocols(engine, arrayOf("h2", "http/1.1"))
            engine
        }

        val clientContext = SSLContext.getInstance("TLS", conscrypt)
        initializeSSLContext(clientContext, keypairCert.second)

        var protocol = ""
        val pipeMiddleware = object : ConscryptMiddleware(clientContext) {
            override suspend fun wrapTlsSocket(session: AsyncHttpClientSession, tlsSocket: AsyncTlsSocket, host: String, port: Int): AsyncSocket {
                protocol = Conscrypt.getApplicationProtocol(tlsSocket.engine)
                return super.wrapTlsSocket(session, tlsSocket, host, port)
            }
            override suspend fun connectInternal(session: AsyncHttpClientSession, host: String, port: Int): AsyncSocket {
                return server.connect()
            }
        }

        val httpServer = AsyncHttpServer {
            // would be cool to pipe hte request right back to the response
            // without buffering, but the http spec does not work that way.
            // entire request must be received before sending a response.
            val data = readAllString(it.body!!)
            assert(data == "hello world")
            AsyncHttpResponse.OK(body = Utf8StringBody(data))
        }

        httpServer.listen(tlsServer)

        var data = ""
        async {
            val client = AsyncHttpClient()
            pipeMiddleware.install(client)
            val connection =
                client.execute(AsyncHttpRequest.POST("https://TestServer", body = Utf8StringBody("hello world")))
            data = readAllString(connection.body!!)
        }

        assert(data == "hello world")
        assert(protocol == "h2")
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
            AsyncHttpResponse.OK(body = Utf8StringBody(data))
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
                val request =
                    AsyncHttpRequest(URI.create("http://example/foo"), "POST", body = Utf8StringBody("hello world"))
                val result = httpClient.execute(request, socket, reader)
                val data = readAllString(result.body!!)
                assert(data == "hello world")
                requestsCompleted++
            }
        }

        assert(requestsCompleted == 3)
    }
}