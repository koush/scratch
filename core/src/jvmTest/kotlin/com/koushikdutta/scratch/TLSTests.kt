package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.createByteBufferList
import com.koushikdutta.scratch.http.Methods
import com.koushikdutta.scratch.http.StatusCode
import com.koushikdutta.scratch.http.body.Utf8StringBody
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.client.AsyncHttpClientSession
import com.koushikdutta.scratch.http.client.execute
import com.koushikdutta.scratch.http.client.middleware.AsyncSocketMiddleware
import com.koushikdutta.scratch.http.client.middleware.ConscryptMiddleware
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import com.koushikdutta.scratch.parser.readAllString
import com.koushikdutta.scratch.tls.*
import org.conscrypt.Conscrypt
import org.junit.Test
import javax.net.ssl.SSLContext
import kotlin.test.assertEquals

class TLSTests {
    @Test
    fun testConscryptTlsServer() {
        val conscrypt = Conscrypt.newProvider()
//        Security.insertProviderAt(conscrypt, 1)

        val keypairCert = createSelfSignedCertificate("TestServer")
        val serverContext = SSLContext.getInstance("TLS", conscrypt)
        serverContext.init(keypairCert.first, keypairCert.second)

        val server = createAsyncPipeServerSocket()
        val tlsServer = server.listenTls {
            serverContext.createSSLEngine("TestServer", 80)
        }


        var data = ""
        tlsServer.acceptAsync {
            data += readAllString(::read)
        }

        for (i in 1..2) {
            async {
                val clientContext = SSLContext.getInstance("TLS", conscrypt)
                clientContext.init(keypairCert.second)

                val client = server.connect().connectTls("TestServer", 80, clientContext)
                client.write(ByteBufferList().putUtf8String("hello world"))
                client.close()
            }
        }

        assertEquals(data, "hello worldhello world")
    }

    @Test
    fun testAlpn() {
        val conscrypt = Conscrypt.newProvider()
        val keypairCert = createSelfSignedCertificate("TestServer")

        val serverContext = SSLContext.getInstance("TLS", conscrypt)
        serverContext.init(keypairCert.first, keypairCert.second)


        val server = createAsyncPipeServerSocket()
        val tlsServer = server.listenTls {
            val engine = serverContext.createSSLEngine()
            Conscrypt.setApplicationProtocols(engine, arrayOf("foo"))
            engine
        }

        val clientContext = SSLContext.getInstance("TLS", conscrypt)
        clientContext.init(keypairCert.second)

        var protocol = ""
        async {
            val tlsClient = tlsServer.accept().iterator().next()
            protocol = Conscrypt.getApplicationProtocol(tlsClient.engine)
            tlsClient::write.drain("hello world".createByteBufferList())
            tlsClient.close()
        }

        var data = ""
        async {
            val socket = server.connect()
            val engine = clientContext.createSSLEngine("TestServer", 0)
            engine.useClientMode = true
            Conscrypt.setApplicationProtocols(engine, arrayOf("foo"))
            val tlsSocket = tlsHandshake(socket, engine)
            data = readAllString(tlsSocket::read)
        }

        assert(data == "hello world")
        assert(protocol == "foo")
    }

    @Test
    fun testHttp2Alpn() {
        val conscrypt = Conscrypt.newProvider()
        val keypairCert = createSelfSignedCertificate("TestServer")
        val client = AsyncHttpClient()

        val serverContext = SSLContext.getInstance("TLS", conscrypt)
        serverContext.init(keypairCert.first, keypairCert.second)


        val server = createAsyncPipeServerSocket()
        val tlsServer = server.listenTls {
            val engine = serverContext.createSSLEngine()
            Conscrypt.setApplicationProtocols(engine, arrayOf("h2", "http/1.1"))
            engine
        }

        val clientContext = SSLContext.getInstance("TLS", conscrypt)
        clientContext.init(keypairCert.second)

        var protocol = ""
        val pipeMiddleware = object : ConscryptMiddleware(client.eventLoop, clientContext) {
            override suspend fun wrapForTlsSocket(session: AsyncHttpClientSession, socket: AsyncSocket, host: String, port: Int): AsyncTlsSocket {
                val tlsSocket = super.wrapForTlsSocket(session, socket, host, port)
                protocol = Conscrypt.getApplicationProtocol(tlsSocket.engine)
                return tlsSocket
            }

            override suspend fun connectInternal(
                session: AsyncHttpClientSession,
                host: String,
                port: Int
            ): AsyncSocket {
                return server.connect()
            }
        }

        val httpServer = AsyncHttpServer {
            // would be cool to pipe hte request right back to the response
            // without buffering, but the http spec does not work that way.
            // entire request must be received before sending a response.
            val data = readAllString(it.body!!)
            assert(data == "hello world")
            StatusCode.OK(body = Utf8StringBody(data))
        }

        httpServer.listen(tlsServer)

        var data = ""
        async {
            pipeMiddleware.install(client)
            data = client.execute(Methods.POST("https://TestServer", body = Utf8StringBody("hello world"))) { readAllString(it.body!!) }

            data += client.execute(Methods.POST("https://TestServer", body = Utf8StringBody("hello world"))) { readAllString(it.body!!) }
        }

        assertEquals((client.middlewares.find { it is AsyncSocketMiddleware }!! as AsyncSocketMiddleware).openHttp2Connections, 1)
        assert(data == "hello worldhello world")
        assert(protocol == "h2")
    }
}
