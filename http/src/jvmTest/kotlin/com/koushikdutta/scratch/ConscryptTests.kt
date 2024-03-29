package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.createByteBufferList
import com.koushikdutta.scratch.http.Methods
import com.koushikdutta.scratch.http.StatusCode
import com.koushikdutta.scratch.http.body.Utf8StringBody
import com.koushikdutta.scratch.http.client.execute
import com.koushikdutta.scratch.http.client.executor.SchemeExecutor
import com.koushikdutta.scratch.http.client.executor.useHttpsAlpnExecutor
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import com.koushikdutta.scratch.parser.parse
import com.koushikdutta.scratch.parser.readString
import com.koushikdutta.scratch.tls.*
import org.conscrypt.Conscrypt
import org.junit.Test
import javax.net.ssl.SSLContext
import kotlin.test.assertEquals

class ConscryptTests {
    @Test
    fun testConscryptTlsServer() {
        val conscrypt = Conscrypt.newProvider()
//        Security.insertProviderAt(conscrypt, 1)

        val keypairCert = createSelfSignedCertificate("TestServer")
        val serverContext = SSLContext.getInstance("TLS", conscrypt)
        serverContext.init(keypairCert.first, keypairCert.second)

        val server = createAsyncPipeServerSocket()
        val tlsServer: AsyncServerSocket<AsyncTlsSocket> = server.listenTls {
            serverContext.createSSLEngine("TestServer", 80)
        }


        var data = ""
        tlsServer.acceptAsync {
            data += this.parse().readString()
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
            tlsClient.drain("hello world".createByteBufferList())
            tlsClient.close()
        }

        var data = ""
        async {
            val socket = server.connect()
            val engine = clientContext.createSSLEngine("TestServer", 0)
            engine.useClientMode = true
            Conscrypt.setApplicationProtocols(engine, arrayOf("foo"))
            val tlsSocket = tlsHandshake(socket, engine)
            data = tlsSocket.parse().readString()
        }

        assert(data == "hello world")
        assert(protocol == "foo")
    }

    @Test
    fun testHttp2Alpn() {
        val conscrypt = Conscrypt.newProvider()
        val keypairCert = createSelfSignedCertificate("TestServer")

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

        var protocol: String? = null
        val client = SchemeExecutor(AsyncAffinity.NO_AFFINITY)
        client.useHttpsAlpnExecutor(AsyncAffinity.NO_AFFINITY, clientContext) {
            createAsyncIterable {
                yield(server.connect())
            }
        }

        val httpServer = AsyncHttpServer {
            // would be cool to pipe hte request right back to the response
            // without buffering, but the http spec does not work that way.
            // entire request must be received before sending a response.
            val data = it.body!!.parse().readString()
            assert(data == "hello world")
            StatusCode.OK(body = Utf8StringBody(data))
        }

        httpServer.listenAsync(tlsServer)

        var data = ""
        async {
            data = client.execute(Methods.POST("https://TestServer", body = Utf8StringBody("hello world"))) { it.body!!.parse().readString() }
            data += client.execute(Methods.POST("https://TestServer", body = Utf8StringBody("hello world"))) {
                protocol = it.headers["X-Scratch-ALPN"]
                it.body!!.parse().readString()
            }
        }

        assert(data == "hello worldhello world")
        assert(protocol == "h2")
    }
}
